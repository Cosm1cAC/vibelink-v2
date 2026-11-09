use super::*;
use std::sync::Arc;

fn owner(id: &str) -> Identity {
    Identity {
        user_id: id.into(),
        label: id.into(),
        beta: false,
    }
}
fn fixture() -> (PathBuf, Shared, Vec<BotRecord>) {
    let (dir, app, _) = crate::runtime_tests::fixture();
    let members = ["bot_cto", "bot_dev", "bot_qa"]
        .iter()
        .map(|id| app.bots.get_bot(id).unwrap())
        .collect();
    app.sessions
        .lock()
        .unwrap()
        .agent_versions
        .insert("pc".into(), ("0.5.0".into(), now_ms()));
    (dir, app, members)
}

#[test]
fn input_dedup_is_durable_scoped_and_rejects_changed_intent() {
    let (dir, app, members) = fixture();
    let request = json!({"requestId":"r1","text":"Make a report"});
    let w = app
        .work
        .submit("g1", &request, owner("one"), members.clone())
        .unwrap();
    let reloaded = WorkStore::load(dir.clone()).unwrap();
    assert_eq!(
        reloaded
            .submit("g1", &request, owner("one"), members.clone())
            .unwrap()
            .id,
        w.id
    );
    assert_eq!(reloaded.messages("g1").len(), 1);
    assert!(
        reloaded
            .submit(
                "g1",
                &json!({"requestId":"r1","text":"Make a report","mode":"parallel"}),
                owner("one"),
                members.clone()
            )
            .is_err()
    );
    assert!(
        reloaded
            .submit("g2", &request, owner("one"), members.clone())
            .is_err()
    );
    assert_ne!(
        reloaded
            .submit("g1", &request, owner("two"), members.clone())
            .unwrap()
            .id,
        w.id
    );
    assert!(
        reloaded
            .submit(
                "g1",
                &json!({"requestId":"r2","text":"follow up","replyToWorkId":w.id}),
                owner("two"),
                members.clone()
            )
            .is_err()
    );
    assert!(reloaded.action("g2", &w.id, "one", "cancel").is_err());
    assert!(reloaded.action("g1", &w.id, "two", "cancel").is_err());
    reloaded.action("g1", &w.id, "one", "cancel").unwrap();
    assert!(
        reloaded
            .submit(
                "g1",
                &json!({"requestId":"r2","text":"follow up","replyToWorkId":w.id}),
                owner("one"),
                members
            )
            .is_err()
    );
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn corrupt_ledger_and_failed_commit_never_become_fresh_empty_work() {
    let (dir, app, members) = fixture();
    fs::create_dir_all(dir.join("group_work.tmp")).unwrap();
    assert!(
        app.work
            .submit(
                "g",
                &json!({"requestId":"r","text":"do work"}),
                owner("one"),
                members
            )
            .is_err()
    );
    assert!(app.work.list("g").is_empty());
    fs::write(dir.join("group_work.json"), b"{invalid").unwrap();
    assert!(WorkStore::load(dir.clone()).is_err());
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn model_plan_is_bounded_readonly_and_cannot_add_outside_members() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r","text":"inspect","delegationMode":"consult"}),
            owner("one"),
            members,
        )
        .unwrap();
    for bad in [
        json!({"steps":[],"acceptance":"x"}),
        json!({"steps":[{"botId":"outside","instruction":"x"}],"acceptance":"x"}),
        json!({"steps":[{"botId":"bot_dev","instruction":"x"},{"botId":"bot_dev","instruction":"x"}],"acceptance":"x"}),
    ] {
        assert!(
            app.work
                .update(&w.id, |w| apply_plan(w, &bad.to_string()))
                .is_err()
        );
        assert!(app.work.get(&w.id).unwrap().steps.is_empty());
    }
    let plan = json!({"steps":[{"botId":"bot_dev","instruction":"inspect source","kind":"work"}],"acceptance":"report file evidence"});
    let w = app
        .work
        .update(&w.id, |w| apply_plan(w, &plan.to_string()))
        .unwrap();
    assert_eq!(
        w.steps.iter().map(|s| s.kind.as_str()).collect::<Vec<_>>(),
        vec!["consult", "review", "deliver"]
    );
    assert!(public(&w).get("members").is_none());
    assert!(public(&w)["steps"][0].get("prompt").is_none());
    fs::remove_dir_all(dir).unwrap();
}

struct MockAgent {
    replies: std::collections::HashMap<String, Value>,
    outputs: std::collections::HashMap<String, String>,
    turns: std::collections::HashMap<String, Vec<Value>>,
    creates: usize,
    prompts: usize,
    calls: Vec<Value>,
    rework: bool,
    reviewed: usize,
    plan: Option<Value>,
    question_bot: Option<String>,
    question_asked: bool,
    split_pages: bool,
    failure_for_current_turn: Option<bool>,
}
impl MockAgent {
    fn new() -> Self {
        Self {
            replies: Default::default(),
            outputs: Default::default(),
            turns: Default::default(),
            creates: 0,
            prompts: 0,
            calls: vec![],
            rework: false,
            reviewed: 0,
            plan: None,
            question_bot: None,
            question_asked: false,
            split_pages: false,
            failure_for_current_turn: None,
        }
    }
    fn respond(&mut self, request: &Value) -> Value {
        let kind = request["kind"].as_str().unwrap();
        let p = &request["payload"];
        if kind.ends_with("create") || kind.ends_with("prompt") {
            let op = p["operationId"].as_str().unwrap().to_string();
            if let Some(reply) = self.replies.get(&op) {
                return reply.clone();
            }
            assert_eq!(kind.starts_with("cli.runtime."), true);
            let prompt = p["text"].as_str().unwrap();
            let stage = if prompt.contains("stepInstruction=为用户明确请求选择") {
                "route"
            } else if prompt.contains("stepInstruction=只读检查执行证据") {
                "review"
            } else if prompt.contains("stepInstruction=结合执行结果") {
                "deliver"
            } else {
                "work"
            };
            if stage != "work" {
                assert_eq!(p["codexRuntime"]["readOnly"], true);
            }
            let sid = if kind.ends_with("create") {
                self.creates += 1;
                format!("official-{}", self.creates)
            } else {
                self.prompts += 1;
                p["session"].as_str().unwrap().into()
            };
            let output=match stage {
                "route"=>self.plan.clone().unwrap_or_else(||json!({"steps":[{"botId":"bot_dev","kind":"work","instruction":"Do the requested work and provide evidence"}],"acceptance":"evidence supplied"})).to_string(),
                "review"=>{self.reviewed+=1;"Evidence checked".into()},
                "deliver" if self.rework&&self.reviewed==1=>json!({"decision":"revise","text":"Fix missing edge case"}).to_string(),
                "deliver"=>json!({"decision":"complete","text":"Delivered with evidence"}).to_string(),
                _ if self.question_bot.as_deref()==p["botId"].as_str()&&!self.question_asked=>{self.question_asked=true;"NEEDS INPUT: Which report format?".into()},
                _=>"Work performed with evidence".into(),
            };
            self.outputs.insert(sid.clone(), output);
            let user = prompt.rsplit("[User Request]\n").next().unwrap();
            let turns = self.turns.entry(sid.clone()).or_default();
            turns.insert(0,json!({"id":format!("{sid}-user-{}",self.prompts),"type":"user","text":user,"time":{"created":now_ms()}}));
            turns.insert(0,json!({"id":format!("{sid}-assistant-{}",self.prompts),"type":"assistant","content":[{"type":"text","text":self.outputs[&sid]}],"time":{"created":now_ms(),"completed":now_ms()}}));
            let reply = json!({"id":sid,"agent":"codex"});
            self.replies.insert(op, reply.clone());
            self.calls.push(request.clone());
            reply
        } else if kind.ends_with("workspace") {
            json!({"cwd":"D:/workspace","baseCwd":"D:/workspace","repoRoot":"D:/workspace","caseSensitive":false,"needsWorktreeInit":false})
        } else if kind.ends_with("lifecycle") {
            json!({"known":true,"quiescent":true})
        } else if kind.ends_with("operation") {
            match self.replies.get(p["operationId"].as_str().unwrap()) {
                Some(result) => json!({"status":"accepted","result":result}),
                None => json!({"status":"missing"}),
            }
        } else if kind.ends_with("messages") {
            let sid = p["session"].as_str().unwrap();
            // A resumed turn gets a distinct assistant ID (never return the old answer as new).
            let data = self.turns.get(sid).cloned().unwrap_or_default();
            if let Some(current)=self.failure_for_current_turn {
                let user=if current {data.iter().find(|m|m["type"]=="user").and_then(|m|m["text"].as_str()).unwrap_or("")}else{"[VibeLink Work:old Step:old]\nold request"};
                return json!({"data":data,"turnError":{"message":"429 input byte limit reached","userText":user,"startedAt":now_ms()}});
            }
            if self.split_pages && data.len() > 1 {
                if p["cursor"].as_str().unwrap_or("").is_empty() {
                    json!({"data":[data[0]],"cursor":{"next":"1"}})
                } else {
                    json!({"data":data[1..]})
                }
            } else {
                json!({"data":data})
            }
        } else {
            json!({"ok":true,"known":true})
        }
    }
}
async fn pump(app: Shared, agent: Arc<Mutex<MockAgent>>) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        loop {
            let request = {
                app.sessions
                    .lock()
                    .unwrap()
                    .queue
                    .get_mut("pc")
                    .and_then(|q| {
                        if q.is_empty() {
                            None
                        } else {
                            Some(q.remove(0))
                        }
                    })
            };
            if let Some(r) = request {
                let response = agent.lock().unwrap().respond(&r);
                if let Some(tx) = app
                    .sessions
                    .lock()
                    .unwrap()
                    .waiters
                    .remove(r["id"].as_str().unwrap())
                {
                    let _ = tx.send(Ok(response));
                }
            } else {
                tokio::time::sleep(std::time::Duration::from_millis(1)).await;
            }
        }
    })
}

#[tokio::test]
async fn durable_route_execute_review_accept_then_followup_resumes_owner_session() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"Implement the requested change"}),
            owner("one"),
            members.clone(),
        )
        .unwrap();
    let agent = Arc::new(Mutex::new(MockAgent::new()));
    let pump = pump(app.clone(), agent.clone()).await;
    run(app.clone(), &w.id).await.unwrap();
    let first = app.work.get(&w.id).unwrap();
    assert_eq!(first.status, "completed");
    assert_eq!(first.steps.len(), 4);
    assert_eq!(first.messages.len(), 2);
    assert_eq!(first.consumed, 1);
    let execution_sid = first.steps[1].session_id.clone();
    let reloaded = WorkStore::load(dir.clone()).unwrap();
    assert_eq!(reloaded.get(&w.id).unwrap().status, "completed");
    app.work.submit("g",&json!({"requestId":"r2","replyToWorkId":w.id,"text":"Adjust the report in the same task"}),owner("one"),members).unwrap();
    run(app.clone(), &w.id).await.unwrap();
    let last = app.work.get(&w.id).unwrap();
    assert_eq!(last.status, "completed");
    assert_eq!(last.steps.len(), 7);
    assert_eq!(last.steps[4].session_id, execution_sid);
    assert_eq!(last.consumed, 2);
    assert_eq!(agent.lock().unwrap().prompts, 1);
    assert_eq!(last.messages.len(), 4);
    pump.abort();
    let _ = pump.await;
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn restart_between_dispatch_and_reply_reuses_operation_receipt() {
    let (dir, mut app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"Do the work"}),
            owner("one"),
            members,
        )
        .unwrap();
    let runner = tokio::spawn({
        let app = app.clone();
        let id = w.id.clone();
        async move { run(app, &id).await }
    });
    let request = loop {
        let request = {
            app.sessions
                .lock()
                .unwrap()
                .queue
                .get_mut("pc")
                .and_then(|q| q.pop())
        };
        if let Some(r) = request {
            if r["kind"] == "cli.runtime.workspace" {
                let response = MockAgent::new().respond(&r);
                let tx = app
                    .sessions
                    .lock()
                    .unwrap()
                    .waiters
                    .remove(r["id"].as_str().unwrap())
                    .unwrap();
                let _ = tx.send(Ok(response));
                continue;
            }
            break r;
        }
        tokio::task::yield_now().await;
    };
    let agent = Arc::new(Mutex::new(MockAgent::new()));
    agent.lock().unwrap().respond(&request); // Accepted by Agent, HTTP/RPC response lost.
    runner.abort();
    let _ = runner.await;
    assert_eq!(app.work.get(&w.id).unwrap().steps[0].status, "dispatching");
    Arc::get_mut(&mut app).unwrap().work = WorkStore::load(dir.clone()).unwrap();
    *app.sessions.lock().unwrap() = crate::SessionPlane::default();
    app.sessions
        .lock()
        .unwrap()
        .agent_versions
        .insert("pc".into(), ("0.5.0".into(), now_ms()));
    let pump = pump(app.clone(), agent.clone()).await;
    run(app.clone(), &w.id).await.unwrap();
    assert_eq!(app.work.get(&w.id).unwrap().status, "completed");
    assert_eq!(agent.lock().unwrap().creates, 4); // Route was accepted only once.
    pump.abort();
    let _ = pump.await;
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn reviewer_gap_returns_to_owner_once_and_is_reviewed_again() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"Do the work"}),
            owner("one"),
            members,
        )
        .unwrap();
    let mut mock = MockAgent::new();
    mock.rework = true;
    let agent = Arc::new(Mutex::new(mock));
    let pump = pump(app.clone(), agent.clone()).await;
    run(app.clone(), &w.id).await.unwrap();
    let w = app.work.get(&w.id).unwrap();
    assert_eq!(w.status, "completed");
    assert_eq!(w.revision, 1);
    assert_eq!(w.steps.len(), 7);
    assert_eq!(w.steps[1].session_id, w.steps[4].session_id);
    assert_eq!(agent.lock().unwrap().reviewed, 2);
    pump.abort();
    let _ = pump.await;
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn old_missing_or_stale_agent_cannot_ignore_operation_fences() {
    let (dir, app, _) = fixture();
    assert!(agent_ready(&app, "pc").is_ok());
    for version in ["0.2.9", "0.2.99", "0.4.0", "junk"] {
        app.sessions
            .lock()
            .unwrap()
            .agent_versions
            .insert("pc".into(), (version.into(), now_ms()));
        assert!(agent_ready(&app, "pc").is_err());
    }
    assert!(version_supports_work("0.10.0"));
    assert!(version_supports_work("1.0.0"));
    app.sessions
        .lock()
        .unwrap()
        .agent_versions
        .insert("pc".into(), ("0.5.0".into(), 0));
    assert!(agent_ready(&app, "pc").is_err());
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn session_reply_must_belong_to_the_dispatched_user_turn() {
    let (dir, app, members) = fixture();
    let mut w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"inspect"}),
            owner("one"),
            members,
        )
        .unwrap();
    add_step(&mut w, "bot_dev", "work", "inspect".into(), "sid".into()).unwrap();
    let step = &w.steps[0];
    let marker = format!("[VibeLink Work:{} Step:{}]", w.id, step.id);
    let user = |text: &str| json!({"type":"user","text":text});
    let assistant = |id: &str, text: &str, completed: u64| json!({"id":id,"type":"assistant","content":[{"type":"text","text":text}],"time":{"completed":completed}});
    assert!(
        completed_reply(
            &json!({"data":[assistant("x","unrelated",3),user("private question")]}),
            &w.id,
            step
        )
        .is_none()
    );
    let data = json!({"data":[assistant("private","private reply",3),user("private question"),assistant("work","correct work reply",2),user(&marker)]});
    assert_eq!(
        completed_reply(&data, &w.id, step).unwrap(),
        "correct work reply"
    );
    assert!(
        completed_reply(
            &json!({"data":[assistant("running","not final",0),user(&marker)]}),
            &w.id,
            step
        )
        .is_none()
    );
    assert_eq!(last_assistant(&data).unwrap()["id"], "private");
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn cancelled_lost_create_looks_up_receipt_without_replaying_execution() {
    let (dir, mut app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"Do the work"}),
            owner("one"),
            members,
        )
        .unwrap();
    let runner = tokio::spawn({
        let app = app.clone();
        let id = w.id.clone();
        async move { run(app, &id).await }
    });
    let request = loop {
        let request = {
            app.sessions
                .lock()
                .unwrap()
                .queue
                .get_mut("pc")
                .and_then(|q| q.pop())
        };
        if let Some(r) = request {
            if r["kind"] == "cli.runtime.workspace" {
                let response = MockAgent::new().respond(&r);
                let tx = app
                    .sessions
                    .lock()
                    .unwrap()
                    .waiters
                    .remove(r["id"].as_str().unwrap())
                    .unwrap();
                let _ = tx.send(Ok(response));
                continue;
            }
            break r;
        }
        tokio::task::yield_now().await;
    };
    let agent = Arc::new(Mutex::new(MockAgent::new()));
    agent.lock().unwrap().respond(&request);
    runner.abort();
    let _ = runner.await;
    app.work.action("g", &w.id, "one", "cancel").unwrap();
    Arc::get_mut(&mut app).unwrap().work = WorkStore::load(dir.clone()).unwrap();
    app.sessions.lock().unwrap().queue.clear();
    let pump = pump(app.clone(), agent.clone()).await;
    run(app.clone(), &w.id).await.unwrap();
    let w = app.work.get(&w.id).unwrap();
    assert_eq!(w.status, "cancelled");
    assert_eq!(w.steps[0].status, "cancelled");
    assert_eq!(w.steps[0].session_id, "official-1");
    assert_eq!(agent.lock().unwrap().creates, 1);
    assert!(app.work.runnable().is_empty());
    pump.abort();
    let _ = pump.await;
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn reply_resumes_questioning_member_and_keeps_the_remaining_plan() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"Do the work"}),
            owner("one"),
            members.clone(),
        )
        .unwrap();
    let mut mock = MockAgent::new();
    mock.question_bot = Some("bot_dev".into());
    mock.plan = Some(
        json!({"steps":[{"botId":"bot_cto","kind":"work","instruction":"first part"},{"botId":"bot_dev","kind":"work","instruction":"second part"},{"botId":"bot_qa","kind":"consult","instruction":"third part"}],"acceptance":"all three parts verified"}),
    );
    let agent = Arc::new(Mutex::new(mock));
    let pump = pump(app.clone(), agent.clone()).await;
    run(app.clone(), &w.id).await.unwrap();
    let blocked = app.work.get(&w.id).unwrap();
    assert_eq!(blocked.status, "needs_input");
    assert_eq!(blocked.blocked_step, Some(2));
    assert_eq!(blocked.steps[3].status, "queued");
    assert!(!public(&blocked)["retryable"].as_bool().unwrap());
    let sid = blocked.steps[2].session_id.clone();
    app.work
        .submit(
            "g",
            &json!({"requestId":"r2","text":"Use Markdown","replyToWorkId":w.id}),
            owner("one"),
            members,
        )
        .unwrap();
    run(app.clone(), &w.id).await.unwrap();
    let completed = app.work.get(&w.id).unwrap();
    assert_eq!(completed.status, "completed");
    assert_eq!(completed.steps[3].bot_id, "bot_dev");
    assert_eq!(completed.steps[3].session_id, sid);
    assert_eq!(completed.steps[4].bot_id, "bot_qa");
    assert_eq!(completed.steps[4].status, "done");
    assert_eq!(agent.lock().unwrap().prompts, 1);
    pump.abort();
    let _ = pump.await;
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn permission_changes_during_a_running_turn_request_interrupt_before_pausing() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"Do the work"}),
            owner("one"),
            members,
        )
        .unwrap();
    let runner = tokio::spawn({
        let app = app.clone();
        let id = w.id.clone();
        async move { run(app, &id).await }
    });
    let mut mock = MockAgent::new();
    let mut interrupted = false;
    loop {
        let request = {
            app.sessions
                .lock()
                .unwrap()
                .queue
                .get_mut("pc")
                .and_then(|q| {
                    if q.is_empty() {
                        None
                    } else {
                        Some(q.remove(0))
                    }
                })
        };
        if let Some(r) = request {
            let result = if r["kind"] == "cli.runtime.messages" {
                app.bots.upsert_bot(&json!({"id":"bot_cto","name":"Chief","approvalMode":"plan","isolationMode":"worktree"})).unwrap();
                json!({"data":[]})
            } else {
                if r["kind"] == "cli.runtime.interrupt" {
                    interrupted = true;
                }
                mock.respond(&r)
            };
            if let Some(tx) = app
                .sessions
                .lock()
                .unwrap()
                .waiters
                .remove(r["id"].as_str().unwrap())
            {
                let _ = tx.send(Ok(result));
            }
        }
        if runner.is_finished() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(1)).await;
    }
    let error = runner.await.unwrap().unwrap_err();
    assert!(error.contains("权限或绑定改变"));
    assert!(interrupted);
    assert_eq!(mock.creates, 1);
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn a_turn_split_across_agent_pages_completes_without_reexecution() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"Do the work"}),
            owner("one"),
            members,
        )
        .unwrap();
    let mut mock = MockAgent::new();
    mock.split_pages = true;
    let agent = Arc::new(Mutex::new(mock));
    let pump = pump(app.clone(), agent.clone()).await;
    run(app.clone(), &w.id).await.unwrap();
    assert_eq!(app.work.get(&w.id).unwrap().status, "completed");
    assert_eq!(agent.lock().unwrap().creates, 4);
    pump.abort();
    let _ = pump.await;
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn readonly_plan_cannot_gain_write_permission_through_rework_or_followup() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"Inspect the issue"}),
            owner("one"),
            members.clone(),
        )
        .unwrap();
    let mut mock = MockAgent::new();
    mock.rework = true;
    mock.plan = Some(
        json!({"steps":[{"botId":"bot_dev","kind":"consult","instruction":"inspect only"}],"acceptance":"evidence supplied"}),
    );
    let agent = Arc::new(Mutex::new(mock));
    let pump = pump(app.clone(), agent.clone()).await;
    run(app.clone(), &w.id).await.unwrap();
    app.work
        .submit(
            "g",
            &json!({"requestId":"r2","text":"Explain the finding","replyToWorkId":w.id}),
            owner("one"),
            members,
        )
        .unwrap();
    run(app.clone(), &w.id).await.unwrap();
    let w = app.work.get(&w.id).unwrap();
    assert_eq!(w.status, "completed");
    assert!(
        w.steps
            .iter()
            .filter(|s| s.bot_id == "bot_dev")
            .all(|s| s.kind == "consult")
    );
    assert!(
        agent
            .lock()
            .unwrap()
            .calls
            .iter()
            .filter(|r| r["payload"]["botId"] == "bot_dev")
            .all(|r| r["payload"]["codexRuntime"]["readOnly"] == true)
    );
    pump.abort();
    let _ = pump.await;
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn cancelling_an_unaccepted_followup_cannot_claim_the_old_session_proves_acceptance() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r1","text":"Do work"}),
            owner("one"),
            members,
        )
        .unwrap();
    app.work
        .update(&w.id, |w| {
            add_step(
                w,
                "bot_dev",
                "work",
                "Follow up".into(),
                "old-session".into(),
            )?;
            w.steps[0].status = "dispatching".into();
            Ok(())
        })
        .unwrap();
    let bot = app.bots.get_bot("bot_dev").unwrap();
    app.bindings.bind(
        "",
        crate::agent_sessions::Binding {
            id: "old-session".into(),
            agent: "codex".into(),
            device: "pc".into(),
            model: bot.model.clone(),
            scope: crate::agent_sessions::scope(&owner("one"), &bot.model),
            owner: owner("one"),
            bot_id: bot.id,
            cwd: bot.cwd,
            work_id: String::new(),
            title: "old session".into(),
        },
    );
    app.work.action("g", &w.id, "one", "cancel").unwrap();
    let agent = Arc::new(Mutex::new(MockAgent::new()));
    let pump = pump(app.clone(), agent.clone()).await;
    run(app.clone(), &w.id).await.unwrap();
    assert_eq!(
        app.work.get(&w.id).unwrap().steps[0].status,
        "cancelled_unknown"
    );
    assert_eq!(agent.lock().unwrap().creates, 0);
    assert_eq!(agent.lock().unwrap().prompts, 0);
    pump.abort();
    let _ = pump.await;
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn archives_preserve_full_export_dedup_and_messages_while_compacting_attachment_storage() {
    let (dir, app, members) = fixture();
    let body = json!({"requestId":"archive-1","text":"Inspect report","files":[{"name":"input.txt","data":"YWJj"}]});
    let w = app
        .work
        .submit("g", &body, owner("one"), members.clone())
        .unwrap();
    assert!(app.work.archive("g", &w.id, "one").is_err());
    app.work
        .update(&w.id, |w| {
            w.status = "completed".into();
            w.result = "Report ready".into();
            Ok(())
        })
        .unwrap();
    assert!(app.work.export("g", &w.id, "other").is_err());
    let archived = app.work.archive("g", &w.id, "one").unwrap();
    assert!(archived.archived);
    assert!(archived.inputs[0].files.is_empty());
    assert!(app.work.search("g", "report", false).is_empty());
    assert_eq!(app.work.search("g", "report", true).len(), 1);
    assert_eq!(
        app.work.export("g", &w.id, "one").unwrap()["inputs"][0]["files"][0]["data"],
        "YWJj"
    );
    assert_eq!(app.work.messages("g").len(), 1);
    assert_eq!(
        app.work
            .submit("g", &body, owner("one"), members)
            .unwrap()
            .id,
        w.id
    );
    let reloaded = WorkStore::load(dir.clone()).unwrap();
    assert!(reloaded.get(&w.id).unwrap().archived);
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn budgets_are_attributed_and_reserved_per_work_without_losing_user_totals() {
    use crate::model_usage::{Tokens, UsageStore, WorkCharge};
    let dir = std::env::temp_dir().join(format!("work_bill_{}", crate::auth::random_token()));
    let usage = Arc::new(UsageStore::load(dir.clone()).unwrap());
    let charge = WorkCharge {
        work_id: "one-work".into(),
        max_tokens: 100,
        max_requests: 1,
    };
    let mut meter = usage
        .begin_work(owner("one"), "responses", 10, 10, Some(charge.clone()))
        .unwrap();
    assert!(
        usage
            .begin_work(owner("one"), "responses", 1, 1, Some(charge.clone()))
            .is_err()
    );
    meter.json(&json!({"usage":{"input_tokens":10,"output_tokens":5,"input_tokens_details":{"cached_tokens":8}}}));
    meter.finish("completed", false);
    assert_eq!(usage.work_summary("one-work")["totalTokens"], 15);
    assert_eq!(usage.work_summary("one-work")["cachedTokens"], 8);
    assert_eq!(
        usage.summary(&owner("one"), &crate::model_usage::day_at(now_ms()))["totalTokens"],
        15
    );
    assert!(
        usage
            .begin_work(owner("two"), "responses", 1, 1, Some(charge))
            .is_err()
    );
    drop(meter);
    drop(usage);
    let usage = Arc::new(UsageStore::load(dir.clone()).unwrap());
    assert_eq!(usage.work_summary("one-work")["requests"], 1);
    let budget = WorkCharge {
        work_id: "tiny".into(),
        max_tokens: 5,
        max_requests: 0,
    };
    assert!(
        usage
            .begin_work(owner("one"), "responses", 4, 4, Some(budget))
            .is_err()
    );
    let _ = std::mem::size_of::<Tokens>();
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn user_cancel_revokes_mandate_and_aliases_cannot_bypass_physical_workspace_identity() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r","text":"inspect"}),
            owner("one"),
            members,
        )
        .unwrap();
    assert!(app.work.charge(&w.id, "one").is_ok());
    app.work.action("g", &w.id, "one", "cancel").unwrap();
    assert!(app.work.charge(&w.id, "one").is_err());
    assert_eq!(physical_device(&crate::host_name()), physical_device("pc"));
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn group_owned_sessions_cannot_be_resumed_through_the_unscoped_private_chat_path() {
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r","text":"inspect"}),
            owner("one"),
            members,
        )
        .unwrap();
    let mut binding=crate::agent_sessions::new_binding(&app,"pc",&json!({"model":crate::model_policy::DEEPSEEK_MODEL,"meterIdentity":owner("one"),"groupWorkId":w.id})).unwrap();
    binding.id = "managed-session".into();
    app.bindings.bind("", binding);
    let error=crate::call_agent(&app,"pc","cli.prompt",json!({"session":"managed-session","text":"bypass work budget","meterIdentity":owner("one")}),1).await.unwrap_err();
    assert!(error.contains("原任务追问"));
    assert!(app.sessions.lock().unwrap().queue.is_empty());
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn completed_messages_without_quiescent_process_proof_cannot_release_a_workspace() {
    use crate::work_leases::{Access, Resource};
    let (dir, app, members) = fixture();
    let w = app
        .work
        .submit(
            "g",
            &json!({"requestId":"r","text":"inspect"}),
            owner("one"),
            members,
        )
        .unwrap();
    let mut w = app
        .work
        .update(&w.id, |w| {
            add_step(w, "bot_dev", "work", "edit".into(), "sid".into())?;
            w.steps[0].status = "done".into();
            Ok(())
        })
        .unwrap();
    let lease = app
        .leases
        .acquire(
            Resource::resolved("dev_shared", "pc", "D:/workspace", false).unwrap(),
            &w.id,
            &w.steps[0].id,
            Access::Write,
        )
        .unwrap();
    app.work
        .update(&w.id, |w| {
            w.steps[0].lease = Some(lease.token.clone());
            Ok(())
        })
        .unwrap();
    workspace::reconcile(&app);
    assert!(app.leases.get(&lease.token).is_some());
    w = app
        .work
        .update(&w.id, |w| {
            w.steps[0].quiescent_proof = true;
            Ok(())
        })
        .unwrap();
    workspace::reconcile(&app);
    assert!(app.leases.get(&lease.token).is_none());
    assert!(w.steps[0].quiescent_proof);
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn a_plan_without_optional_refinement_keeps_the_user_acceptance() {
    let (dir, app, members) = fixture();
    let w = app.work.submit("g", &json!({"requestId":"original", "text":"Create marker.txt containing GROUP_OK and verify the actual file"}), owner("one"), members).unwrap();
    let plan = json!({"steps":[{"botId":"bot_dev","kind":"work","instruction":"Create and verify marker.txt","acceptance":"GROUP_OK"}]});
    let w = app.work.update(&w.id, |w| apply_plan(w, &plan.to_string())).unwrap();
    assert!(w.acceptance.contains(&w.mandate.acceptance));
    assert!(w.acceptance.contains("actual file"));
    assert_eq!(w.steps[0].bot_id, "bot_dev");
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn legacy_unknown_dispatch_stays_readonly_even_after_its_workspace_barrier_was_restored() {
    let (dir, app, members) = fixture();
    let w = app.work.submit("g", &json!({"requestId":"legacy", "text":"inspect"}), owner("one"), members).unwrap();
    app.work.update(&w.id, |w| {
        add_step(w, "bot_dev", "work", "inspect".into(), String::new())?;
        let s = &mut w.steps[0];
        s.status = "dispatching".into();
        s.device = "pc".into();
        s.dispatched_at = now_ms();
        s.prompt = "legacy operation whose receipt was lost".into();
        Ok(())
    }).unwrap();
    let agent = Arc::new(Mutex::new(MockAgent::new()));
    let task = pump(app.clone(), agent.clone()).await;
    workspace::restore_legacy(&app).await.unwrap();
    let restored = WorkStore::load(dir.clone()).unwrap().get(&w.id).unwrap();
    assert!(restored.steps[0].reconcile_only);
    assert!(!restored.steps[0].workspace.is_empty());
    for _ in 0..2 {
        assert!(execute(&app, &w.id, 0).await.unwrap_err().contains("未重新派发"));
    }
    let step = app.work.get(&w.id).unwrap().steps[0].clone();
    assert!(step.reconcile_only);
    assert!(app.leases.get(step.lease.as_ref().unwrap()).is_some());
    assert_eq!(agent.lock().unwrap().creates, 0);
    assert_eq!(agent.lock().unwrap().prompts, 0);
    task.abort();
    let _ = task.await;
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn failed_executor_turn_is_not_completion_evidence_and_old_turn_errors_are_ignored() {
    for current in [true,false] {
        let (dir, app, members) = fixture();
        let w=app.work.submit("g",&json!({"requestId":"quota","text":"inspect"}),owner("one"),members).unwrap();
        let mut mock=MockAgent::new();mock.failure_for_current_turn=Some(current);
        let agent=Arc::new(Mutex::new(mock));let task=pump(app.clone(),agent.clone()).await;
        let result=run(app.clone(),&w.id).await;
        if current {
            assert!(result.unwrap_err().contains("429 input byte limit"));
            assert_ne!(app.work.get(&w.id).unwrap().steps[0].status,"done");
            assert_eq!(agent.lock().unwrap().creates,1);
        } else {
            result.unwrap();assert_eq!(app.work.get(&w.id).unwrap().status,"completed");
        }
        task.abort();let _=task.await;std::fs::remove_dir_all(dir).unwrap();
    }
}

#[test]
fn observation_revision_is_monotonic_independent_of_review_cycle_and_durable() {
    let (dir,app,members)=fixture();
    let w=app.work.submit("g",&json!({"requestId":"obs-1","text":"Observe me"}),owner("one"),members).unwrap();
    assert_eq!(app.work.get(&w.id).unwrap().observation_revision,1);
    app.work.update(&w.id,|w|{w.status="completed".into();w.revision=9;Ok(())}).unwrap();
    assert_eq!(app.work.get(&w.id).unwrap().observation_revision,2);
    app.work.update(&w.id,|w|{w.result="metadata only".into();Ok(())}).unwrap();
    assert_eq!(app.work.get(&w.id).unwrap().observation_revision,2);
    app.work.update(&w.id,|w|{w.status="queued".into();w.revision=0;Ok(())}).unwrap();
    app.work.update(&w.id,|w|{w.status="completed".into();Ok(())}).unwrap();
    assert_eq!(app.work.get(&w.id).unwrap().observation_revision,4);
    let reloaded=WorkStore::load(dir.clone()).unwrap();
    reloaded.with_observation_snapshot(|observations|{assert_eq!(observations[0].revision,4);assert_eq!(observations[0].status,"completed");});
    let mut disk:Value=serde_json::from_slice(&fs::read(dir.join("group_work.json")).unwrap()).unwrap();
    disk["work"][0].as_object_mut().unwrap().remove("observationRevision");
    fs::write(dir.join("group_work.json"),serde_json::to_vec(&disk).unwrap()).unwrap();
    assert_eq!(WorkStore::load(dir.clone()).unwrap().get(&w.id).unwrap().observation_revision,0);
    fs::remove_dir_all(dir).unwrap();
}
