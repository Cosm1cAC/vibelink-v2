//! 「配对手机」窗口：向 hub 要一个一次性配对链接，画成二维码给 VibeLink 扫。
//!
//! hub 侧：`POST /api/pair/start`（需要管理员 token）→ `{id, link, urls, expiresAt}`；
//! 之后每 2 秒查 `GET /api/pair/status/{id}`：手机扫到码先变成 scanned（显示「请在手机上确认」），
//! 手机上点「连接」后变成 paired（显示「已连接：设备名」）。
use crate::{http_get, http_post_body, parse_hub, Settings};
use eframe::egui::{self, Color32};
use qrcodegen::{QrCode, QrCodeEcc};
use std::{
    sync::{Arc, Mutex},
    thread,
    time::Duration,
};
use vibelink_protocol::now_ms;

#[derive(Clone, Default)]
pub struct PairState {
    /// 每次「生成」加一；旧的轮询线程看到代数变了就自己退出
    pub generation: u64,
    pub loading: bool,
    pub link: String,
    pub urls: Vec<String>,
    pub expires_at: u64,
    /// pending / scanned（手机已扫码待确认）/ paired / expired
    pub status: String,
    pub paired_name: String,
    pub error: Option<String>,
}

pub struct PairWindow {
    pub open: bool,
    state: Arc<Mutex<PairState>>,
    qr: Option<(String, QrCode)>,
}

impl Default for PairWindow {
    fn default() -> Self {
        Self { open: false, state: Arc::new(Mutex::new(PairState::default())), qr: None }
    }
}

fn friendly(e: String) -> String {
    match e.as_str() {
        "HTTP 401" | "HTTP 403" => "需要管理员 token：在「设置」里填 hub 数据目录下 token.txt 的内容".into(),
        "HTTP 404" => "hub 太旧：不支持二维码配对，请先运行 install\\deploy-secure-hub.ps1 升级".into(),
        _ => e,
    }
}

/// 配对要管理员 token。设置里存的可能只是 client token（deploy 脚本会这样配），
/// 所以本机就是 hub 时优先用 hub 数据目录里的 token.txt。
fn admin_token(s: &Settings) -> String {
    let local = s.hub.contains("127.0.0.1") || s.hub.contains("localhost") || s.hub.contains("192.168.137.1");
    if local {
        let dir = std::env::var("VIBELINK_DATA").map(std::path::PathBuf::from).unwrap_or_else(|_| {
            std::path::PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_default()).join("vibelink-mesh").join("data")
        });
        if let Ok(t) = std::fs::read_to_string(dir.join("dev").join("token.txt")) {
            if !t.trim().is_empty() {
                return t.trim().to_string();
            }
        }
    }
    s.token.clone()
}

impl PairWindow {
    pub fn toggle(&mut self, settings: &Settings) {
        self.open = !self.open;
        if self.open {
            self.generate(settings);
        } else {
            self.stop();
        }
    }

    fn stop(&self) {
        self.state.lock().unwrap().generation += 1;
    }

    pub fn generate(&mut self, settings: &Settings) {
        let my_gen = {
            let mut st = self.state.lock().unwrap();
            let g = st.generation + 1;
            *st = PairState { generation: g, loading: true, ..Default::default() };
            g
        };
        self.qr = None;
        let shared = self.state.clone();
        let s = settings.clone();
        let token = admin_token(settings);
        thread::spawn(move || {
            let started = (|| -> Result<(String, String, Vec<String>, u64), String> {
                let ep = parse_hub(&s.hub)?;
                let body = http_post_body(ep, "/api/pair/start", &token, r#"{"name":"phone"}"#, "application/json")?;
                let v: serde_json::Value = serde_json::from_str(&body).map_err(|e| e.to_string())?;
                let link = v.get("link").and_then(|x| x.as_str()).unwrap_or("").to_string();
                if link.is_empty() {
                    return Err("hub 太旧：不支持二维码配对，请先升级 vibelink-hub".into());
                }
                let id = v.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
                let urls = v
                    .get("urls")
                    .and_then(|x| x.as_array())
                    .map(|a| a.iter().filter_map(|u| u.as_str().map(String::from)).collect())
                    .unwrap_or_default();
                let exp = v.get("expiresAt").and_then(|x| x.as_u64()).unwrap_or(0);
                Ok((id, link, urls, exp))
            })();
            let id = {
                let mut st = shared.lock().unwrap();
                if st.generation != my_gen {
                    return;
                }
                st.loading = false;
                match started {
                    Ok((id, link, urls, exp)) => {
                        st.link = link;
                        st.urls = urls;
                        st.expires_at = exp;
                        st.status = "pending".into();
                        id
                    }
                    Err(e) => {
                        st.error = Some(friendly(e));
                        return;
                    }
                }
            };
            // 等手机扫码
            loop {
                thread::sleep(Duration::from_secs(2));
                if shared.lock().unwrap().generation != my_gen {
                    return;
                }
                let Ok(ep) = parse_hub(&s.hub) else { return };
                let Ok(body) = http_get(ep, &format!("/api/pair/status/{id}"), &token) else { continue };
                let Ok(v) = serde_json::from_str::<serde_json::Value>(&body) else { continue };
                let status = v.get("status").and_then(|x| x.as_str()).unwrap_or("pending").to_string();
                let mut st = shared.lock().unwrap();
                if st.generation != my_gen {
                    return;
                }
                st.paired_name = v.get("name").and_then(|x| x.as_str()).unwrap_or("").to_string();
                st.status = status.clone();
                if status != "pending" && status != "scanned" {
                    return;
                }
            }
        });
    }

    pub fn show(&mut self, ctx: &egui::Context, settings: &Settings) {
        if !self.open {
            return;
        }
        ctx.request_repaint_after(Duration::from_millis(500));
        let st = self.state.lock().unwrap().clone();
        if !st.link.is_empty() && self.qr.as_ref().map(|(l, _)| l != &st.link).unwrap_or(true) {
            self.qr = QrCode::encode_text(&st.link, QrCodeEcc::Medium).ok().map(|q| (st.link.clone(), q));
        }
        let mut open = true;
        let mut regenerate = false;
        egui::Window::new("配对手机")
            .open(&mut open)
            .collapsible(false)
            .resizable(false)
            .default_width(340.0)
            .show(ctx, |ui| {
                ui.vertical_centered(|ui| {
                    if st.loading {
                        ui.add_space(40.0);
                        ui.spinner();
                        ui.label("正在向 hub 申请配对码…");
                        ui.add_space(40.0);
                    } else if let Some(err) = &st.error {
                        ui.add_space(12.0);
                        ui.colored_label(Color32::from_rgb(248, 113, 113), err);
                        ui.add_space(8.0);
                        regenerate = ui.button("重试").clicked();
                    } else if st.status == "paired" {
                        ui.add_space(30.0);
                        ui.label(
                            egui::RichText::new(format!("✔ 已配对：{}", st.paired_name))
                                .size(18.0)
                                .color(Color32::from_rgb(52, 211, 153)),
                        );
                        ui.label(egui::RichText::new("这台手机拿到了自己的 token，可以在「设置」里随时吊销").small().weak());
                        ui.add_space(12.0);
                        regenerate = ui.button("再配一台").clicked();
                        ui.add_space(20.0);
                    } else if st.status == "scanned" {
                        ui.add_space(30.0);
                        let who = if st.paired_name.is_empty() { "手机".to_string() } else { st.paired_name.clone() };
                        ui.label(
                            egui::RichText::new(format!("📱 {who} 已扫码"))
                                .size(18.0)
                                .color(Color32::from_rgb(96, 165, 250)),
                        );
                        ui.label("请在手机上点「连接」完成配对");
                        ui.add_space(12.0);
                        regenerate = ui.button("不是我的手机，作废这个码").clicked();
                        ui.add_space(20.0);
                    } else {
                        let left = st.expires_at.saturating_sub(now_ms()) / 1000;
                        let expired = st.status == "expired" || left == 0;
                        if let Some((_, qr)) = &self.qr {
                            draw_qr(ui, qr, 280.0, expired);
                        }
                        ui.add_space(6.0);
                        if expired {
                            ui.colored_label(Color32::from_rgb(251, 191, 36), "二维码已过期");
                            regenerate = ui.button("重新生成").clicked();
                        } else {
                            ui.label("打开手机 VibeLink「远程会话 › 扫码连接」扫一扫");
                            ui.label(egui::RichText::new(format!("一次性，{:02}:{:02} 后过期", left / 60, left % 60)).small().weak());
                            if ui.small_button("复制配对链接（扫不了码时粘贴到手机）").clicked() {
                                ui.ctx().copy_text(st.link.clone());
                            }
                        }
                    }
                });
                if !st.urls.is_empty() && st.status != "paired" {
                    ui.add_space(6.0);
                    ui.collapsing("手机会依次尝试这些地址", |ui| {
                        for u in &st.urls {
                            ui.label(egui::RichText::new(u).monospace().small());
                        }
                        ui.label(
                            egui::RichText::new("公网地址写在 hub 数据目录的 public-url.txt（或环境变量 VIBELINK_PUBLIC_URL）")
                                .small()
                                .weak(),
                        );
                    });
                }
            });
        if regenerate {
            self.generate(settings);
        }
        if !open {
            self.open = false;
            self.stop();
        }
    }
}

/// 白底黑块 + 4 个模块宽的静区（扫码器需要）。过期时整体变灰，避免有人去扫一个没用的码。
fn draw_qr(ui: &mut egui::Ui, qr: &QrCode, max_px: f32, faded: bool) {
    let n = qr.size();
    let quiet = 4;
    let total = n + quiet * 2;
    let module = (max_px / total as f32).floor().max(2.0);
    let side = module * total as f32;
    let (resp, painter) = ui.allocate_painter(egui::vec2(side, side), egui::Sense::hover());
    let origin = resp.rect.min;
    let (bg, fg) = if faded {
        (Color32::from_gray(90), Color32::from_gray(60))
    } else {
        (Color32::WHITE, Color32::BLACK)
    };
    painter.rect_filled(resp.rect, 4.0, bg);
    for y in 0..n {
        for x in 0..n {
            if qr.get_module(x, y) {
                let min = origin + egui::vec2((x + quiet) as f32 * module, (y + quiet) as f32 * module);
                painter.rect_filled(egui::Rect::from_min_size(min, egui::vec2(module, module)), 0.0, fg);
            }
        }
    }
}
