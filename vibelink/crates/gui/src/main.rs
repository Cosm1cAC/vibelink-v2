//! mesh GUI - egui desktop panel (Windows/macOS), talks to the hub over plain HTTP.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use eframe::egui::{self, Align2, Color32, FontId, Stroke};
use vibelink_protocol::{ConfigInfo, Device, Event};
use serde::{Deserialize, Serialize};
use tray_icon::{
    menu::{Menu, MenuEvent, MenuItem},
    TrayIcon, TrayIconBuilder, TrayIconEvent,
};
use std::{
    io::{Read, Write},
    net::{SocketAddr, TcpStream, ToSocketAddrs},
    path::PathBuf,
    process::Command,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, Sender},
        Arc, Mutex,
    },
    thread,
    time::Duration,
};
#[cfg(windows)]
use std::os::windows::process::CommandExt;

const POLL: Duration = Duration::from_secs(3);

#[derive(Serialize, Deserialize, Clone)]
struct Settings {
    hub: String,
    token: String,
}

impl Default for Settings {
    fn default() -> Self {
        Self { hub: "http://192.168.137.1:18995".into(), token: String::new() }
    }
}

// ---------------- tailscale ----------------
// The Windows backend follows the tray client: if tailscale-ipn.exe is not
// running, tailscaled falls back to NoState. The watchdog handles that in the
// background; this chip is the manual override + visibility.
#[derive(Clone, Default)]
struct TailscaleInfo {
    state: String,
    ip: Option<String>,
    auth_url: Option<String>,
}

fn tailscale_exe() -> &'static str {
    if cfg!(windows) {
        r"C:\Program Files\Tailscale\tailscale.exe"
    } else {
        "tailscale"
    }
}

fn hidden(program: &str) -> Command {
    let mut c = Command::new(program);
    #[cfg(windows)]
    {
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    c
}

fn read_tailscale() -> TailscaleInfo {
    let out = match hidden(tailscale_exe()).args(["status", "--json"]).output() {
        Ok(o) => o,
        Err(_) => return TailscaleInfo { state: "missing".into(), ..Default::default() },
    };
    let v: serde_json::Value = match serde_json::from_slice(&out.stdout) {
        Ok(v) => v,
        Err(_) => return TailscaleInfo { state: "unknown".into(), ..Default::default() },
    };
    TailscaleInfo {
        state: v.get("BackendState").and_then(|x| x.as_str()).unwrap_or("unknown").to_string(),
        ip: v
            .get("TailscaleIPs")
            .and_then(|x| x.as_array())
            .and_then(|a| a.first())
            .and_then(|x| x.as_str())
            .map(str::to_string),
        auth_url: v
            .get("AuthURL")
            .and_then(|x| x.as_str())
            .filter(|s| !s.is_empty())
            .map(str::to_string),
    }
}

fn spawn_tailscale_poller(shared: Arc<Mutex<TailscaleInfo>>) {
    thread::spawn(move || loop {
        let info = read_tailscale();
        if let Ok(mut g) = shared.lock() {
            *g = info;
        }
        thread::sleep(Duration::from_secs(5));
    });
}

fn tailscale_action(info: TailscaleInfo) {
    thread::spawn(move || {
        if info.state == "NeedsLogin" {
            if let Some(url) = info.auth_url {
                let _ = hidden("cmd").args(["/c", "start", "", &url]).spawn();
            }
            return;
        }
        #[cfg(windows)]
        {
            let running = hidden("tasklist")
                .args(["/FI", "IMAGENAME eq tailscale-ipn.exe", "/NH"])
                .output()
                .map(|o| String::from_utf8_lossy(&o.stdout).to_ascii_lowercase().contains("tailscale-ipn"))
                .unwrap_or(false);
            if !running {
                let _ = hidden(r"C:\Program Files\Tailscale\tailscale-ipn.exe").spawn();
                thread::sleep(Duration::from_secs(2));
            }
        }
        let _ = hidden(tailscale_exe()).arg("up").output();
    });
}

fn config_path() -> PathBuf {
    let base = if cfg!(windows) {
        std::env::var("APPDATA").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from("."))
    } else {
        PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join("Library/Application Support")
    };
    base.join("vibelink").join("config.json")
}

fn load_settings() -> Settings {
    let from_file = std::fs::read_to_string(config_path())
        .ok()
        .and_then(|s| serde_json::from_str::<Settings>(&s).ok());
    if let Some(s) = from_file {
        return s;
    }
    // first run: allow VIBELINK_HUB to seed the hub URL (used by the macOS LaunchAgent)
    let mut s = Settings::default();
    if let Ok(hub) = std::env::var("VIBELINK_HUB") {
        if !hub.trim().is_empty() {
            s.hub = hub.trim().to_string();
        }
    }
    s
}

fn save_settings(s: &Settings) {
    let p = config_path();
    if let Some(dir) = p.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(txt) = serde_json::to_string_pretty(s) {
        let _ = std::fs::write(p, txt);
    }
}

// ---------------- minimal HTTP GET ----------------
fn http_get(ep: (&str, u16), path: &str, token: &str) -> Result<String, String> {
    let addr: SocketAddr = (ep.0, ep.1).to_socket_addrs().map_err(|e| e.to_string())?.next().ok_or("resolve failed")?;
    let mut s = TcpStream::connect_timeout(&addr, Duration::from_secs(3)).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(Duration::from_secs(6))).ok();
    let req = format!(
        "GET {path} HTTP/1.1\r\nHost: {}:{}\r\nx-mesh-token: {token}\r\nconnection: close\r\n\r\n",
        ep.0, ep.1
    );
    s.write_all(req.as_bytes()).map_err(|e| e.to_string())?;
    let mut raw = Vec::new();
    s.read_to_end(&mut raw).map_err(|e| e.to_string())?;
    let text = String::from_utf8_lossy(&raw).to_string();
    let (head, body) = text.split_once("\r\n\r\n").ok_or("malformed response")?;
    let status: u16 = head
        .lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|v| v.parse().ok())
        .ok_or("bad status")?;
    if status != 200 {
        return Err(format!("HTTP {status}"));
    }
    Ok(body.to_string())
}

fn parse_hub(hub: &str) -> Result<(&str, u16), String> {
    let rest = hub.trim_end_matches('/');
    let rest = rest.strip_prefix("http://").ok_or("hub 必须是 http:// 开头")?;
    match rest.rsplit_once(':') {
        Some((h, p)) => Ok((h, p.parse().map_err(|_| "端口不对")?)),
        None => Ok((rest, 80)),
    }
}

fn http_post(ep: (&str, u16), path: &str, token: &str, body: &str, ctype: &str) -> Result<String, String> {
    let addr: SocketAddr = (ep.0, ep.1).to_socket_addrs().map_err(|e| e.to_string())?.next().ok_or("resolve failed")?;
    let mut s = TcpStream::connect_timeout(&addr, Duration::from_secs(3)).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(Duration::from_secs(6))).ok();
    let req = format!(
        "POST {path} HTTP/1.1\r\nHost: {}:{}\r\nx-mesh-token: {token}\r\ncontent-type: {ctype}\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
        ep.0,
        ep.1,
        body.len()
    );
    s.write_all(req.as_bytes()).map_err(|e| e.to_string())?;
    let mut raw = Vec::new();
    s.read_to_end(&mut raw).map_err(|e| e.to_string())?;
    let text = String::from_utf8_lossy(&raw).to_string();
    let (head, _body) = text.split_once("\r\n\r\n").ok_or("malformed response")?;
    let status: u16 = head
        .lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|v| v.parse().ok())
        .ok_or("bad status")?;
    if status >= 300 {
        return Err(format!("HTTP {status}"));
    }
    Ok(text)
}

#[derive(Deserialize)]
struct DevicesResp {
    devices: Vec<Device>,
}

#[derive(Deserialize)]
struct EventsResp {
    events: Vec<Event>,
}

#[derive(Deserialize)]
struct ConfigsResp {
    configs: Vec<ConfigInfo>,
}

struct Snapshot {
    devices: Vec<Device>,
    events: Vec<Event>,
    configs: Vec<ConfigInfo>,
    policy: Option<serde_json::Value>,
    error: Option<String>,
}

fn spawn_poller(settings: Arc<Mutex<Settings>>, tx: Sender<Snapshot>, wake: Arc<AtomicBool>) {
    thread::spawn(move || loop {
        let s = settings.lock().unwrap().clone();
        let result = (|| -> Result<Snapshot, String> {
            let ep = parse_hub(&s.hub)?;
            let d: DevicesResp = serde_json::from_str(&http_get(ep, "/api/devices", &s.token)?).map_err(|e| e.to_string())?;
            let e: EventsResp = serde_json::from_str(&http_get(ep, "/api/events?limit=60", &s.token)?).map_err(|e| e.to_string())?;
            let c: ConfigsResp = serde_json::from_str(&http_get(ep, "/api/configs", &s.token)?).map_err(|e| e.to_string())?;
            let p = serde_json::from_str::<serde_json::Value>(&http_get(ep, "/api/policy", &s.token)?).ok();
            Ok(Snapshot { devices: d.devices, events: e.events, configs: c.configs, policy: p, error: None })
        })();
        let snap = result.unwrap_or_else(|e| Snapshot { devices: Vec::new(), events: Vec::new(), configs: Vec::new(), policy: None, error: Some(e) });
        if tx.send(snap).is_err() {
            break;
        }
        // sleep in slices so the 刷新 button feels instant
        let mut slept = Duration::ZERO;
        while slept < POLL {
            if wake.swap(false, Ordering::Relaxed) {
                break;
            }
            thread::sleep(Duration::from_millis(250));
            slept += Duration::from_millis(250);
        }
    });
}

// ---------------- app ----------------
struct App {
    settings: Arc<Mutex<Settings>>,
    rx: Receiver<Snapshot>,
    devices: Vec<Device>,
    events: Vec<Event>,
    error: Option<String>,
    selected: Option<String>,
    edit_hub: String,
    edit_token: String,
    show_settings: bool,
    last_ok: bool,
    wake: Arc<AtomicBool>,
    last_update: Option<std::time::Instant>,
    configs: Vec<ConfigInfo>,
    proxy_input: String,
    show_configs: bool,
    cfg_name: String,
    cfg_body: String,
    last_action: Option<(String, std::time::Instant)>,
    svc: String,
    policy: Option<serde_json::Value>,
    show_policy: bool,
    policy_text: String,
    policy_msg: Option<(String, std::time::Instant)>,
    #[allow(dead_code)] // kept alive on purpose: dropping it removes the tray icon
    tray: Option<TrayIcon>,
    hidden: bool,
    hide_sent: bool,
    quitting: bool,
    tooltip: String,
    ts: TailscaleInfo,
    ts_shared: Arc<Mutex<TailscaleInfo>>,
}

const SERVICES: &[&str] = &["WProxy", "WProxyHysteria", "PcProxyFailover", "PcLanGateway", "PhoneConfigHttp", "VibelinkAgent"];

impl App {
    fn new(cc: &eframe::CreationContext<'_>, start_hidden: bool) -> Self {
        let mut fonts = egui::FontDefinitions::default();
        let candidates = [
            r"C:\Windows\Fonts\msyh.ttc",
            r"C:\Windows\Fonts\msyh.ttf",
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/STHeiti Medium.ttc",
        ];
        if std::env::var("VIBELINK_FONT").ok().as_deref() != Some("none") {
            for path in candidates {
                if let Ok(bytes) = std::fs::read(path) {
                    fonts.font_data.insert("cjk".to_owned(), Arc::new(egui::FontData::from_owned(bytes)));
                    if let Some(f) = fonts.families.get_mut(&egui::FontFamily::Proportional) {
                        f.insert(0, "cjk".to_owned());
                    }
                    if let Some(f) = fonts.families.get_mut(&egui::FontFamily::Monospace) {
                        f.push("cjk".to_owned());
                    }
                    break;
                }
            }
        }
        cc.egui_ctx.set_fonts(fonts);
        cc.egui_ctx.set_visuals(egui::Visuals::dark());

        let settings = load_settings();
        let shared = Arc::new(Mutex::new(settings.clone()));
        let (tx, rx) = mpsc::channel();
        let wake = Arc::new(AtomicBool::new(false));
        spawn_poller(shared.clone(), tx, wake.clone());

        let ts_shared = Arc::new(Mutex::new(TailscaleInfo::default()));
        spawn_tailscale_poller(ts_shared.clone());

        // eframe only calls `logic()` while the window is hidden if a repaint is
        // requested, so keep a slow heartbeat running for the tray menu.
        let bg = cc.egui_ctx.clone();
        thread::spawn(move || loop {
            thread::sleep(Duration::from_millis(500));
            bg.request_repaint();
        });

        Self {
            settings: shared,
            rx,
            devices: Vec::new(),
            events: Vec::new(),
            error: None,
            selected: None,
            edit_hub: settings.hub,
            edit_token: settings.token,
            show_settings: false,
            last_ok: false,
            wake,
            last_update: None,
            configs: Vec::new(),
            proxy_input: String::new(),
            show_configs: false,
            cfg_name: "cmfa-lan-gateway.yaml".into(),
            cfg_body: String::new(),
            last_action: None,
            svc: "WProxy".into(),
            policy: None,
            show_policy: false,
            policy_text: String::new(),
            policy_msg: None,
            tray: build_tray(),
            hidden: start_hidden,
            hide_sent: false,
            quitting: false,
            tooltip: String::new(),
            ts: TailscaleInfo::default(),
            ts_shared,
        }
    }

    fn apply_settings(&mut self) {
        let s = Settings { hub: self.edit_hub.trim().to_string(), token: self.edit_token.trim().to_string() };
        save_settings(&s);
        *self.settings.lock().unwrap() = s;
        self.show_settings = false;
    }
}

fn dot(ui: &mut egui::Ui, color: Color32) {
    let (rect, _) = ui.allocate_exact_size(egui::vec2(10.0, 10.0), egui::Sense::hover());
    ui.painter().circle_filled(rect.center(), 4.0, color);
}

impl eframe::App for App {
    fn logic(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.pump();
        // close button -> hide to tray instead of quitting (no tray => real close)
        if !self.quitting && ctx.input(|i| i.viewport().close_requested()) {
            if self.tray.is_some() {
                ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
                ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
                self.hidden = true;
                self.hide_sent = true;
            } else {
                self.quitting = true;
            }
        }

        while let Ok(ev) = TrayIconEvent::receiver().try_recv() {
            if let TrayIconEvent::Click { button: tray_icon::MouseButton::Left, button_state: tray_icon::MouseButtonState::Up, .. } = ev {
                self.show_window(ctx);
            }
        }
        while let Ok(ev) = MenuEvent::receiver().try_recv() {
            match ev.id.0.as_str() {
                "show" => self.show_window(ctx),
                "quit" => {
                    self.quitting = true;
                    ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                }
                _ => {}
            }
        }
        if self.hidden {
            ctx.request_repaint_after(Duration::from_millis(500));
        }
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        if self.hidden {
            if !self.hide_sent {
                ui.ctx().send_viewport_cmd(egui::ViewportCommand::Visible(false));
                self.hide_sent = true;
            }
            return;
        }
        // Some DPI setups report a root Ui wider than the window; clamp so the
        // right-hand controls stay inside the frame.
        let screen_w = ui.ctx().input(|i| i.viewport_rect().width());
        if ui.available_width() > screen_w + 0.5 {
            ui.set_max_width(screen_w);
        }
        if let Ok(snap) = self.rx.try_recv() {
            self.apply_snapshot(snap);
        }
        if let Ok(g) = self.ts_shared.lock() {
            self.ts = g.clone();
        }
        ui.ctx().request_repaint_after(POLL);

        egui::Panel::top("top").show(ui, |ui| {
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button(if self.show_settings { "收起" } else { "设置" }).clicked() {
                        self.show_settings = !self.show_settings;
                    }
                    if ui.button("配置").clicked() {
                        self.show_configs = !self.show_configs;
                    }
                    if ui.button("自愈").clicked() {
                        self.show_policy = !self.show_policy;
                        if self.show_policy {
                            if let Some(p) = &self.policy {
                                self.policy_text = serde_json::to_string_pretty(p).unwrap_or_default();
                            }
                        }
                    }
                    if let Some(p) = &self.policy {
                        let on = p.get("enabled").and_then(|v| v.as_bool()).unwrap_or(false);
                        let dry = p.get("dryRun").and_then(|v| v.as_bool()).unwrap_or(true);
                        let (txt, col) = if !on {
                            ("自愈 关", Color32::from_rgb(125, 138, 163))
                        } else if dry {
                            ("自愈 dry-run", Color32::from_rgb(251, 191, 36))
                        } else {
                            ("自愈 生效", Color32::from_rgb(52, 211, 153))
                        };
                        ui.label(egui::RichText::new(txt).small().color(col));
                    }
                    if ui.button("刷新").clicked() {
                        self.wake.store(true, Ordering::Relaxed);
                        ui.ctx().request_repaint();
                    }
                    ui.with_layout(egui::Layout::left_to_right(egui::Align::Center), |ui| {
                        ui.heading("vibelink");
                        ui.add_space(6.0);
                        dot(ui, if self.last_ok { Color32::from_rgb(52, 211, 153) } else { Color32::from_rgb(248, 113, 113) });
                        let hub = self.settings.lock().unwrap().hub.replace("http://", "");
                        ui.add(egui::Label::new(egui::RichText::new(hub).monospace().small()).truncate());
                        if let Some(err) = &self.error {
                            ui.colored_label(Color32::from_rgb(248, 113, 113), format!("· {err}"));
                        }
                        ui.separator();
                        let (ts_txt, ts_col) = match self.ts.state.as_str() {
                            "Running" => (
                                format!("Tailscale {}", self.ts.ip.clone().unwrap_or_default()),
                                Color32::from_rgb(52, 211, 153),
                            ),
                            "Starting" => ("Tailscale 启动中".to_string(), Color32::from_rgb(251, 191, 36)),
                            "NeedsLogin" => ("Tailscale 需登录".to_string(), Color32::from_rgb(251, 191, 36)),
                            "missing" => ("Tailscale 未安装".to_string(), Color32::from_rgb(125, 138, 163)),
                            other => (format!("Tailscale {other}"), Color32::from_rgb(248, 113, 113)),
                        };
                        dot(ui, ts_col);
                        if ui
                            .add(egui::Button::new(egui::RichText::new(ts_txt).small().color(ts_col)).frame(false))
                            .on_hover_text("点击：拉起托盘 / 重连 / 打开登录页")
                            .clicked()
                        {
                            tailscale_action(self.ts.clone());
                        }
                    });
                });
            });
            if self.show_settings {
                ui.add_space(6.0);
                ui.horizontal(|ui| {
                    ui.label("Hub");
                    ui.add(egui::TextEdit::singleline(&mut self.edit_hub).desired_width(320.0));
                    ui.label("Token");
                    ui.add(egui::TextEdit::singleline(&mut self.edit_token).password(true).desired_width(260.0));
                    if ui.button("保存").clicked() {
                        self.apply_settings();
                    }
                });
                ui.add_space(4.0);
            }
            ui.add_space(4.0);
        });

        egui::Panel::left("devices").resizable(true).default_size(250.0).min_size(210.0).max_size(380.0).show(ui, |ui| {
            ui.add_space(6.0);
            ui.label(egui::RichText::new("设备").small().weak());
            ui.add_space(4.0);
            egui::ScrollArea::vertical().show(ui, |ui| {
                for d in &self.devices {
                    let id = d.report.id.clone();
                    let selected = self.selected.as_deref() == Some(id.as_str());
                    let resp = ui.add(
                        egui::Button::new("")
                            .min_size(egui::vec2(ui.available_width(), 52.0))
                            .fill(if selected { Color32::from_rgb(30, 41, 59) } else { Color32::TRANSPARENT }),
                    );
                    let rect = resp.rect;
                    let p = ui.painter();
                    let c = if d.online { Color32::from_rgb(52, 211, 153) } else { Color32::from_rgb(248, 113, 113) };
                    p.circle_filled(egui::pos2(rect.left() + 14.0, rect.top() + 16.0), 4.0, c);
                    p.text(
                        egui::pos2(rect.left() + 26.0, rect.top() + 17.0),
                        Align2::LEFT_CENTER,
                        &d.report.hostname,
                        FontId::proportional(14.0),
                        Color32::from_rgb(217, 226, 242),
                    );
                    let sub = d.report.proxy.server.clone().unwrap_or_else(|| "直连".into());
                    let lat = d.report.latency.as_ref().and_then(|l| l.connect_ms.or(l.tcp_ms));
                    p.text(
                        egui::pos2(rect.left() + 26.0, rect.top() + 36.0),
                        Align2::LEFT_CENTER,
                        format!("{sub}{}", lat.map(|v| format!(" · {v}ms")).unwrap_or_default()),
                        FontId::monospace(11.0),
                        Color32::from_rgb(125, 138, 163),
                    );
                    if resp.clicked() {
                        self.selected = Some(id);
                    }
                    ui.add_space(2.0);
                }
            });
        });

        egui::Panel::bottom("events").resizable(true).default_size(150.0).min_size(80.0).show(ui, |ui| {
            ui.add_space(4.0);
            ui.label(egui::RichText::new("事件").small().weak());
            egui::ScrollArea::vertical().stick_to_bottom(true).show(ui, |ui| {
                for e in self.events.iter().rev().take(60).rev() {
                    let t = ms_to_clock(e.at);
                    ui.horizontal(|ui| {
                        ui.label(egui::RichText::new(t).monospace().small().weak());
                        ui.label(egui::RichText::new(e.device.clone().unwrap_or_else(|| "hub".into())).monospace().small().weak());
                        ui.label(egui::RichText::new(&e.message).small());
                    });
                }
            });
        });

        egui::CentralPanel::default().show(ui, |ui| {
            egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                self.topology(ui);
                ui.add_space(8.0);
                self.detail(ui);
            });
        });

        let ctx = ui.ctx().clone();
        self.configs_window(&ctx);
        self.policy_window(&ctx);
    }
}

impl App {
    fn topology(&self, ui: &mut egui::Ui) {
        let w = (ui.available_width() - 24.0).clamp(320.0, 900.0);
        let (resp, painter) = ui.allocate_painter(egui::vec2(w, 200.0), egui::Sense::hover());
        let rect = resp.rect;
        painter.rect_filled(rect, 10.0, Color32::from_rgb(18, 23, 34));
        painter.rect_stroke(rect, 10.0, Stroke::new(1.0, Color32::from_rgb(35, 44, 61)), egui::StrokeKind::Inside);

        let hub_pos = egui::pos2(rect.left() + 110.0, rect.center().y);
        painter.circle_filled(hub_pos, 26.0, Color32::from_rgb(30, 58, 138));
        painter.text(hub_pos, Align2::CENTER_CENTER, "HUB", FontId::proportional(13.0), Color32::WHITE);
        if let Ok(s) = self.settings.lock() {
            painter.text(
                hub_pos + egui::vec2(0.0, 38.0),
                Align2::CENTER_CENTER,
                format!(":{}", parse_hub(&s.hub).map(|(_, p)| p).unwrap_or(0)),
                FontId::monospace(10.0),
                Color32::from_rgb(125, 138, 163),
            );
        }

        let n = self.devices.len().max(1);
        let step = (rect.height() - 60.0) / n as f32;
        let node_x = rect.left() + (rect.width() * 0.58).clamp(300.0, 560.0);
        for (i, d) in self.devices.iter().enumerate() {
            let y = rect.top() + 30.0 + step * (i as f32 + 0.5);
            let pos = egui::pos2(node_x, y);
            let color = if d.online { Color32::from_rgb(52, 211, 153) } else { Color32::from_rgb(248, 113, 113) };
            painter.line_segment([hub_pos, pos], Stroke::new(1.0, Color32::from_rgb(40, 52, 72)));
            painter.circle_filled(pos, 6.0, color);
            painter.text(
                pos + egui::vec2(14.0, -8.0),
                Align2::LEFT_CENTER,
                &d.report.hostname,
                FontId::proportional(13.0),
                Color32::from_rgb(217, 226, 242),
            );
            let sub = d.report.proxy.server.clone().unwrap_or_else(|| "直连".into());
            painter.text(
                pos + egui::vec2(14.0, 9.0),
                Align2::LEFT_CENTER,
                sub,
                FontId::monospace(10.0),
                Color32::from_rgb(125, 138, 163),
            );
        }
    }

    fn detail(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.selected.clone() else {
            ui.label(egui::RichText::new("选择左侧设备查看详情").weak());
            return;
        };
        let Some(d) = self.devices.iter().find(|d| d.report.id == id).cloned() else { return };
        let r = d.report.clone();
        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.heading(&r.hostname);
                dot(ui, if d.online { Color32::from_rgb(52, 211, 153) } else { Color32::from_rgb(248, 113, 113) });
                ui.label(egui::RichText::new(&r.id).monospace().weak());
                ui.label(egui::RichText::new(&r.platform).small().weak());
                ui.label(egui::RichText::new(&r.arch).small().weak());
            });
            ui.add_space(6.0);
            egui::Grid::new("detail").num_columns(2).spacing([16.0, 6.0]).show(ui, |ui| {
                row(ui, "IP", &r.ips.join(", "));
                row(ui, "网关", r.gateway.as_deref().unwrap_or("—"));
                row(ui, "系统代理", r.proxy.server.as_deref().unwrap_or("未启用"));
                if let Some(l) = &r.latency {
                    row(ui, "出口探测", &format!("{} · TCP {}ms · CONNECT {}ms", l.target, fmt_opt(l.tcp_ms), fmt_opt(l.connect_ms)));
                }
                let ports: Vec<String> = r.ports.iter().filter(|(_, v)| **v).map(|(k, _)| k.clone()).collect();
                row(ui, "监听", &ports.join(" "));
                row(ui, "内存", &format!("{}MB 空闲 / {}MB", r.mem.free, r.mem.total));
                row(ui, "心跳", &format!("{}s 前", d.age_ms / 1000));
                row(ui, "opencode", &r.opencode.map(|p| format!(":{p}")).unwrap_or_else(|| "—".into()));
            });
            ui.add_space(10.0);
            ui.separator();
            ui.add_space(6.0);
            ui.horizontal_wrapped(|ui| {
                ui.label(egui::RichText::new("系统代理").small().weak());
                ui.add(egui::TextEdit::singleline(&mut self.proxy_input).desired_width(170.0).hint_text("host:port"));
                if ui.button("设置").clicked() {
                    if !self.proxy_input.trim().is_empty() {
                        let server = self.proxy_input.trim().to_string();
                        self.send_command(ui, "set-proxy", serde_json::json!({ "server": server }));
                    }
                }
                if ui.button("清除").clicked() {
                    self.send_command(ui, "clear-proxy", serde_json::json!({}));
                }
            });
            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                ui.label(egui::RichText::new("服务").small().weak());
                let mut svc = self.svc.clone();
                egui::ComboBox::from_id_salt("svc").selected_text(svc.clone()).width(150.0).show_ui(ui, |ui| {
                    for s in SERVICES {
                        ui.selectable_value(&mut svc, (*s).to_string(), *s);
                    }
                });
                self.svc = svc;
                if ui.button("重启").clicked() {
                    let name = self.svc.clone();
                    self.send_command(ui, "restart-service", serde_json::json!({ "name": name }));
                }
                if ui.button("探测").clicked() {
                    self.send_command(ui, "probe", serde_json::json!({}));
                }
                if let Some((msg, at)) = &self.last_action {
                    ui.label(egui::RichText::new(format!("{msg} · {}s", at.elapsed().as_secs())).small().weak());
                }
            });
        });
    }

    fn apply_snapshot(&mut self, snap: Snapshot) {
        self.last_ok = snap.error.is_none();
        self.error = snap.error;
        self.last_update = Some(std::time::Instant::now());
        if !snap.devices.is_empty() || self.devices.is_empty() {
            self.devices = snap.devices;
        }
        if !snap.events.is_empty() || self.events.is_empty() {
            self.events = snap.events;
        }
        if !snap.configs.is_empty() || self.configs.is_empty() {
            self.configs = snap.configs;
        }
        if self.selected.is_none() {
            self.selected = self.devices.first().map(|d| d.report.id.clone());
        }
        if self.proxy_input.is_empty() {
            if let Some(d) = self.selected.as_ref().and_then(|id| self.devices.iter().find(|d| &d.report.id == id)) {
                self.proxy_input = d.report.proxy.server.clone().unwrap_or_default();
            }
        }
        self.policy = snap.policy;
        self.update_tooltip();
    }

    fn pump(&mut self) {
        while let Ok(snap) = self.rx.try_recv() {
            self.apply_snapshot(snap);
        }
    }

    /// Tray tooltip: "vibelink · 2/2 在线 · 出口 192.168.137.156:7897"
    fn update_tooltip(&mut self) {
        let total = self.devices.len();
        let online = self.devices.iter().filter(|d| d.online).count();
        let egress = self
            .devices
            .iter()
            .find(|d| d.report.id == "pc")
            .and_then(|d| d.report.proxy.server.clone())
            .or_else(|| self.devices.iter().find_map(|d| d.report.proxy.server.clone()));
        let text = match (total, egress) {
            (0, _) => "vibelink · 还没有设备".to_string(),
            (_, Some(e)) => format!("vibelink · {online}/{total} 在线 · 出口 {e}"),
            _ => format!("vibelink · {online}/{total} 在线"),
        };
        let text = match self.ts.state.as_str() {
            "Running" => format!("{text} · TS {}", self.ts.ip.clone().unwrap_or_default()),
            "" | "missing" => text,
            other => format!("{text} · TS {other}"),
        };
        if text != self.tooltip {
            if let Some(tray) = &self.tray {
                let _ = tray.set_tooltip(Some(&text));
            }
            self.tooltip = text;
        }
    }

    fn show_window(&mut self, ctx: &egui::Context) {
        ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
        ctx.send_viewport_cmd(egui::ViewportCommand::Focus);
        self.hidden = false;
        self.hide_sent = false;
        ctx.request_repaint();
    }

    fn send_command(&mut self, ui: &egui::Ui, action: &str, args: serde_json::Value) {
        let s = self.settings.lock().unwrap().clone();
        let Some(device) = self.selected.clone() else { return };
        let body = serde_json::json!({ "device": device, "action": action, "args": args }).to_string();
        let name = action.to_string();
        thread::spawn(move || {
            if let Ok(ep) = parse_hub(&s.hub) {
                let _ = http_post(ep, "/api/commands", &s.token, &body, "application/json");
            }
        });
        self.last_action = Some((format!("{name} 已发送"), std::time::Instant::now()));
        self.wake.store(true, Ordering::Relaxed);
        ui.ctx().request_repaint();
    }

    fn configs_window(&mut self, ctx: &egui::Context) {
        if !self.show_configs {
            return;
        }
        let mut open = self.show_configs;
        let hub = self.settings.lock().unwrap().hub.trim_end_matches('/').to_string();
        let token = self.settings.lock().unwrap().token.clone();
        egui::Window::new("配置版本库").open(&mut open).default_width(560.0).show(ctx, |ui| {
            if self.configs.is_empty() {
                ui.label(egui::RichText::new("还没有配置").weak());
            }
            for c in &self.configs {
                let url = format!("{hub}/phone/{}?t={token}", c.name);
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new(&c.name).monospace().strong());
                    ui.label(egui::RichText::new(format!("{} 个版本", c.versions.len())).small().weak());
                    if ui.button("复制下载链接").clicked() {
                        ui.ctx().copy_text(url.clone());
                    }
                });
                ui.label(egui::RichText::new(url).small().weak());
                ui.add_space(4.0);
            }
            ui.separator();
            ui.add_space(6.0);
            ui.label(egui::RichText::new("上传新版本").small().weak());
            ui.horizontal(|ui| {
                ui.label("名字");
                ui.add(egui::TextEdit::singleline(&mut self.cfg_name).desired_width(280.0));
            });
            ui.add(egui::TextEdit::multiline(&mut self.cfg_body).desired_rows(8).hint_text("粘贴 YAML…"));
            if ui.button("上传").clicked() && !self.cfg_body.trim().is_empty() {
                let s = self.settings.lock().unwrap().clone();
                let name = self.cfg_name.clone();
                let body = self.cfg_body.clone();
                thread::spawn(move || {
                    if let Ok(ep) = parse_hub(&s.hub) {
                        let _ = http_post(ep, &format!("/api/config/{name}"), &s.token, &body, "text/plain");
                    }
                });
                self.last_action = Some(("配置已上传".into(), std::time::Instant::now()));
                self.wake.store(true, Ordering::Relaxed);
            }
        });
        self.show_configs = open;
    }

    fn policy_window(&mut self, ctx: &egui::Context) {
        if !self.show_policy {
            return;
        }
        let mut open = self.show_policy;
        egui::Window::new("自愈策略").open(&mut open).default_width(640.0).show(ctx, |ui| {
            ui.label(egui::RichText::new("enabled / dryRun / rules / watchdogs").small().weak());
            ui.add_space(4.0);
            ui.add(
                egui::TextEdit::multiline(&mut self.policy_text)
                    .desired_rows(18)
                    .code_editor()
                    .desired_width(f32::INFINITY),
            );
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                if ui.button("保存").clicked() {
                    let s = self.settings.lock().unwrap().clone();
                    let body = self.policy_text.clone();
                    thread::spawn(move || {
                        if let Ok(ep) = parse_hub(&s.hub) {
                            let _ = http_post(ep, "/api/policy", &s.token, &body, "application/json");
                        }
                    });
                    self.policy_msg = Some(("已保存".into(), std::time::Instant::now()));
                    self.wake.store(true, Ordering::Relaxed);
                }
                if ui.button("重新加载").clicked() {
                    if let Some(p) = &self.policy {
                        self.policy_text = serde_json::to_string_pretty(p).unwrap_or_default();
                    }
                }
                if let Some((m, t)) = &self.policy_msg {
                    ui.label(egui::RichText::new(format!("{m} · {}s", t.elapsed().as_secs())).small().weak());
                }
            });
        });
        self.show_policy = open;
    }
}

fn row(ui: &mut egui::Ui, k: &str, v: &str) {
    ui.label(egui::RichText::new(k).weak().small());
    ui.label(egui::RichText::new(v).monospace().small());
    ui.end_row();
}

fn fmt_opt(v: Option<u64>) -> String {
    v.map(|x| x.to_string()).unwrap_or_else(|| "—".into())
}

fn ms_to_clock(ms: u64) -> String {
    let secs = (ms / 1000) % 86_400;
    format!("{:02}:{:02}:{:02}", secs / 3600, (secs / 60) % 60, secs % 60)
}

fn build_tray() -> Option<TrayIcon> {
    let menu = Menu::new();
    let show = MenuItem::with_id("show", "显示窗口", true, None);
    let quit = MenuItem::with_id("quit", "退出", true, None);
    menu.append_items(&[&show, &quit]).ok()?;
    let icon = tray_icon::Icon::from_rgba(make_icon_rgba(32), 32, 32).ok()?;
    TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip("vibelink · 多设备控制面")
        .with_icon(icon)
        .build()
        .ok()
}

/// 32x32 RGBA: dark disc + green core, same palette as the UI.
fn make_icon_rgba(size: u32) -> Vec<u8> {
    // dark rounded square + green hub + three blue nodes (matches assets/vibelink.ico)
    let s = size as f32;
    let mut px = vec![0u8; (size * size * 4) as usize];
    let bg: [u8; 3] = [18, 23, 34];
    let edge: [u8; 3] = [43, 58, 85];
    let hub_c: [u8; 3] = [52, 211, 153];
    let node_c: [u8; 3] = [96, 165, 250];

    let cx = s * 0.50;
    let cy = s * 0.52;
    let orbit = s * 0.27;
    let r_node = (s * 0.085).max(1.2);
    let r_hub = (s * 0.11).max(1.5);
    let w_line = (s * 0.035).max(1.0);
    let radius = s * 0.22;
    let half = s * 0.5 - s * 0.03;

    let nodes: Vec<(f32, f32)> = [210.0f32, 330.0, 90.0]
        .iter()
        .map(|a| {
            let r = a.to_radians();
            (cx + orbit * r.cos(), cy - orbit * r.sin())
        })
        .collect();

    for y in 0..size {
        for x in 0..size {
            let fx = x as f32 + 0.5;
            let fy = y as f32 + 0.5;
            let i = ((y * size + x) * 4) as usize;

            let qx = (fx - s / 2.0).abs();
            let qy = (fy - s / 2.0).abs();
            let inside = if qx > half - radius && qy > half - radius {
                let dx = qx - (half - radius);
                let dy = qy - (half - radius);
                dx * dx + dy * dy <= radius * radius
            } else {
                qx <= half && qy <= half
            };
            if !inside {
                continue;
            }

            px[i..i + 3].copy_from_slice(&bg);
            px[i + 3] = 255;
            if half - qx.max(qy) < s * 0.012 {
                px[i..i + 3].copy_from_slice(&edge);
            }

            for (nx, ny) in &nodes {
                let (dx, dy) = (nx - cx, ny - cy);
                let len2 = (dx * dx + dy * dy).max(0.001);
                let t = (((fx - cx) * dx + (fy - cy) * dy) / len2).clamp(0.0, 1.0);
                let (lx, ly) = (cx + t * dx, cy + t * dy);
                if (fx - lx).powi(2) + (fy - ly).powi(2) <= w_line * w_line {
                    px[i..i + 3].copy_from_slice(&node_c);
                }
            }
            for (nx, ny) in &nodes {
                if (fx - nx).powi(2) + (fy - ny).powi(2) <= r_node * r_node {
                    px[i..i + 3].copy_from_slice(&node_c);
                }
            }
            if (fx - cx).powi(2) + (fy - cy).powi(2) <= r_hub * r_hub {
                px[i..i + 3].copy_from_slice(&hub_c);
            }
        }
    }
    px
}

fn main() -> Result<(), eframe::Error> {
    let minimized = std::env::args().any(|a| a == "--minimized");
    let (w, h) = std::env::var("VIBELINK_SIZE")
        .ok()
        .and_then(|s| {
            let (a, b) = s.split_once('x')?;
            Some((a.parse::<f32>().ok()?, b.parse::<f32>().ok()?))
        })
        .unwrap_or((1080.0, 720.0));
    let options = eframe::NativeOptions {
        multisampling: 0,
        depth_buffer: 0,
        stencil_buffer: 0,
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([w, h])
            .with_min_inner_size([820.0, 560.0])
            .with_visible(!minimized)
            .with_icon(egui::IconData { rgba: make_icon_rgba(64), width: 64, height: 64 }),
        ..Default::default()
    };
    eframe::run_native("vibelink", options, Box::new(move |cc| Ok(Box::new(App::new(cc, minimized)))))
}














