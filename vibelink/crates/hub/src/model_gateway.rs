//! DeepSeek-only Magpie gateway. Subscription GPT never enters this transport.
use crate::{
    Shared, auth,
    model_policy::DEEPSEEK_MODEL,
    model_usage::{Identity, Meter,WorkCharge},
};
use axum::{
    Json,
    body::{Body, Bytes},
    extract::{Path, Request, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use futures_util::Stream;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    pin::Pin,
    sync::Mutex,
    task::{Context, Poll},
};
use vibelink_protocol::now_ms;

#[derive(Default)]
pub struct Grants(Mutex<HashMap<String, (Identity, u64,Option<WorkCharge>)>>);
impl Grants {
    pub fn issue(&self, identity: Identity) -> String {
        self.issue_work(identity,None)
    }
    pub fn issue_work(&self,identity:Identity,task:Option<WorkCharge>)->String {
        let mut grants = self.0.lock().unwrap();
        grants.retain(|_, (_, expires,_)| *expires > now_ms());
        let token = auth::random_token();
        grants.insert(token.clone(), (identity, now_ms() + 2 * 60 * 60_000,task));
        token
    }
    pub fn verify(&self, token: &str) -> Option<Identity> {
        self.verify_work(token).map(|(identity,_)|identity)
    }
    pub fn verify_work(&self,token:&str)->Option<(Identity,Option<WorkCharge>)> {
        self.0
            .lock()
            .unwrap()
            .get(token)
            .filter(|(_, expires,_)| *expires > now_ms())
            .map(|(id, _,task)| (id.clone(),task.clone()))
    }
    pub fn revoke_work(&self,id:&str) {
        self.0.lock().unwrap().retain(|_,(_,_,t)|t.as_ref().is_none_or(|t|t.work_id!=id));
    }
}
pub fn identity(ctx: &auth::AuthCtx) -> Identity {
    let tenant = crate::tenants::extract_tenant_id(&ctx.name);
    Identity {
        user_id: tenant.map(str::to_string).unwrap_or_else(|| {
            if ctx.scope == auth::Scope::Admin {
                "owner".into()
            } else {
                format!("client_{}", ctx.token_id)
            }
        }),
        label: ctx.name.clone(),
        beta: tenant.is_some(),
    }
}
pub fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(std::time::Duration::from_secs(5))
        .timeout(std::time::Duration::from_secs(300))
        .build()
        .expect("Magpie client")
}
pub fn upstream() -> String {
    std::env::var("VIBELINK_MAGPIE_URL")
        .unwrap_or_else(|_| "http://127.0.0.1:3425/v1".into())
        .trim_end_matches('/')
        .into()
}

pub async fn proxy(State(app): State<Shared>, Path(rest): Path<String>, req: Request) -> Response {
    let rest = rest.strip_prefix("v1/").unwrap_or(&rest);
    let identity = req
        .extensions()
        .get::<Identity>()
        .cloned()
        .or_else(|| req.extensions().get::<auth::AuthCtx>().map(identity));
    let Some(identity) = identity else {
        return StatusCode::UNAUTHORIZED.into_response();
    };
    let task=req.extensions().get::<WorkCharge>().cloned();
    if let Some(task)=&task {
        let work_app=if app.work.charge(&task.work_id,&identity.user_id).is_ok(){Some(app.clone())}else{app.tenant_apps.lock().unwrap().get(&identity.user_id).cloned()};
        if work_app.is_none_or(|a|a.work.charge(&task.work_id,&identity.user_id).is_err()) {
            return (StatusCode::FORBIDDEN,Json(json!({"error":"Work authorization revoked or completed"}))).into_response();
        }
    }
    if rest == "models" && req.method() == axum::http::Method::GET {
        return Json(json!({"object":"list","data":[{"id":DEEPSEEK_MODEL,"object":"model","owned_by":"magpie","display_name":"DeepSeek V4.1 Flash"}]})).into_response();
    }
    if !matches!(rest, "responses" | "chat/completions") || req.method() != axum::http::Method::POST
    {
        return (
            StatusCode::FORBIDDEN,
            Json(json!({"error":"Only the shared DeepSeek route is available"})),
        )
            .into_response();
    }
    let bytes = match axum::body::to_bytes(req.into_body(), 8 * 1024 * 1024).await {
        Ok(b) => b,
        Err(_) => return StatusCode::PAYLOAD_TOO_LARGE.into_response(),
    };
    let mut body = match serde_json::from_slice::<Value>(&bytes) {
        Ok(Value::Object(o)) => Value::Object(o),
        _ => {
            return (
                StatusCode::BAD_REQUEST,
                Json(json!({"error":"JSON object required"})),
            )
                .into_response();
        }
    };
    if body["model"].as_str().is_some_and(|m| m != DEEPSEEK_MODEL) {
        return (
            StatusCode::FORBIDDEN,
            Json(json!({"error":"Model is not available on this channel"})),
        )
            .into_response();
    }
    if !body["previous_response_id"].is_null() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({"error":"Use agent-bound session history instead of server response IDs"})),
        )
            .into_response();
    }
    body["model"] = json!(DEEPSEEK_MODEL);
    body["user"] = json!(&identity.user_id);
    let streaming = body["stream"].as_bool().unwrap_or(false);
    let output_key = if rest == "responses" {
        "max_output_tokens"
    } else {
        "max_tokens"
    };
    let output = body[output_key].as_u64().unwrap_or(16_384).clamp(1, 32_768);
    body[output_key] = json!(output);
    if rest == "responses" {
        body["store"] = json!(false);
    } else if streaming {
        body["stream_options"] = json!({"include_usage":true});
    }
    let mut meter = match app.usage.begin_work(identity, rest, bytes.len() as u64, output,task) {
        Ok(m) => m,
        Err(e) => return (StatusCode::TOO_MANY_REQUESTS, Json(json!({"error":e}))).into_response(),
    };
    let result = client()
        .post(format!("{}/{rest}", app.magpie))
        .bearer_auth("magpie")
        .json(&body)
        .send()
        .await;
    let response = match result {
        Ok(r) => r,
        Err(_) => {
            meter.finish("error", true);
            return (
                StatusCode::BAD_GATEWAY,
                Json(json!({"error":"Magpie unavailable"})),
            )
                .into_response();
        }
    };
    let status = response.status();
    meter.status(status.as_u16());
    if !status.is_success() {
        meter.finish("error", false);
        return (
            status,
            Json(json!({"error":format!("Magpie returned HTTP {status}")})),
        )
            .into_response();
    }
    let content_type = response
        .headers()
        .get(axum::http::header::CONTENT_TYPE)
        .cloned();
    if streaming {
        let stream = MeteredStream {
            inner: Box::pin(response.bytes_stream()),
            meter,
        };
        let mut builder = Response::builder().status(status);
        if let Some(ct) = content_type {
            builder = builder.header(axum::http::header::CONTENT_TYPE, ct);
        }
        builder
            .header(axum::http::header::CACHE_CONTROL, "no-store")
            .body(Body::from_stream(stream))
            .unwrap()
    } else {
        match response.json::<Value>().await {
            Ok(v) => {
                meter.json(&v);
                meter.finish("completed", true);
                Json(v).into_response()
            }
            Err(_) => {
                meter.finish("error", true);
                (
                    StatusCode::BAD_GATEWAY,
                    Json(json!({"error":"Invalid Magpie response"})),
                )
                    .into_response()
            }
        }
    }
}
struct MeteredStream {
    inner: Pin<Box<dyn Stream<Item = Result<Bytes, reqwest::Error>> + Send>>,
    meter: Meter,
}
impl Stream for MeteredStream {
    type Item = Result<Bytes, reqwest::Error>;
    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        match self.inner.as_mut().poll_next(cx) {
            Poll::Ready(Some(Ok(bytes))) => {
                self.meter.chunk(&bytes);
                Poll::Ready(Some(Ok(bytes)))
            }
            Poll::Ready(Some(Err(e))) => {
                self.meter.finish("error", true);
                Poll::Ready(Some(Err(e)))
            }
            Poll::Ready(None) => {
                self.meter.chunk(b"\n\n");
                self.meter.finish("completed", true);
                Poll::Ready(None)
            }
            Poll::Pending => Poll::Pending,
        }
    }
}
