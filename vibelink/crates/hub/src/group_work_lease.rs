use super::*;
use crate::work_leases::{Access, ReleaseEvidence, Resource};

pub(super) fn runtime(w: &Work, s: &Step, base: bool) -> Value {
    json!({"workspace":if base {&s.base_workspace}else{&s.workspace},"workId":w.id,"stepId":s.id,"mandateId":w.id,"version":w.mandate.version,
        "allowWrite":s.kind=="work"&&!w.mandate.read_only(&s.bot_id)})
}
pub(super) async fn bind(
    app: &Shared,
    id: &str,
    index: usize,
    device: &str,
) -> Result<Step, String> {
    let mut w = app.work.get(id)?;
    let mut s = w.steps[index].clone();
    if s.lease.is_none() {
        let probe=crate::call_agent(app,device,"cli.workspace",json!({"cwd":s.bot.cwd,"botId":s.bot_id,"model":s.bot.model,"meterIdentity":w.owner,
            "isolationMode":s.bot.isolation_mode,"groupWorkConsult":s.kind!="work"||w.mandate.read_only(&s.bot_id)}),15).await?;
        let cwd = probe["cwd"]
            .as_str()
            .ok_or("Agent workspace probe omitted canonical cwd")?
            .to_string();
        let base = probe["baseCwd"].as_str().unwrap_or(&cwd).to_string();
        let resource_path = probe["repoRoot"].as_str().unwrap_or(&base);
        let namespace = if app.beta_mode {
            format!("beta_{}", w.owner.user_id)
        } else {
            "dev_shared".into()
        };
        let resource = Resource::resolved(
            &namespace,
            device,
            resource_path,
            probe["caseSensitive"].as_bool().unwrap_or(false),
        )?;
        let access = if s.kind == "work" && !w.mandate.read_only(&s.bot_id) {
            Access::Write
        } else {
            Access::Read
        };
        let lease = app.leases.acquire(resource, &w.id, &s.id, access)?;
        let state = if probe["needsWorktreeInit"] == true
            && access == Access::Write
            && s.status == "queued"
        {
            "preparing"
        } else {
            "ready"
        };
        w = app.work.update(id, |w| {
            let s = &mut w.steps[index];
            s.device = device.into();
            s.lease = Some(lease.token);
            s.workspace = cwd;
            s.base_workspace = base;
            s.workspace_state = state.into();
            Ok(())
        })?;
        s = w.steps[index].clone();
    }
    let token = s.lease.as_ref().ok_or("Missing workspace lease")?;
    if app.leases.get(token).is_none() {
        return Err("Stale workspace fencing token; result cannot be accepted".into());
    }
    if s.workspace_state == "preparing" {
        let result=crate::call_agent(app,device,"cli.workspace.prepare",json!({"cwd":s.base_workspace,"botId":s.bot_id,"model":s.bot.model,"meterIdentity":w.owner,
            "isolationMode":"worktree","operationId":format!("{}_{}_prepare",w.id,s.id),"operationDevice":s.device,"groupWorkId":w.id,"groupWork":runtime(&w,&s,true)}),30).await;
        let result = match result {
            Ok(v) => v,
            Err(error) => {
                app.leases.mark_uncertain(token, &error)?;
                return Err(error);
            }
        };
        let cwd = result["cwd"]
            .as_str()
            .ok_or("Worktree preparation omitted canonical cwd")?
            .to_string();
        w = app.work.update(id, |w| {
            w.steps[index].workspace = cwd;
            w.steps[index].workspace_state = "ready".into();
            Ok(())
        })?;
        s = w.steps[index].clone();
    }
    Ok(s)
}
pub(super) async fn restore_legacy(app: &Shared) -> Result<(), String> {
    let pending: Vec<_> = {
        let disk = app.work.inner.lock().unwrap();
        disk.work
            .iter()
            .flat_map(|w| {
                w.steps
                    .iter()
                    .enumerate()
                    .filter(|(_, s)| {
                        s.lease.is_none()
                            && s.dispatched_at > 0
                            && !matches!(s.status.as_str(), "done" | "cancelled")
                    })
                    .map(|(i, s)| (w.id.clone(), i, s.device.clone()))
            })
            .collect()
    };
    for (id, index, device) in pending {
        app.work.update(&id, |w| {
            if w.steps[index].status == "dispatching" {
                w.steps[index].reconcile_only = true;
            }
            Ok(())
        })?;
        bind(app, &id, index, &physical_device(&device)).await?;
    }
    Ok(())
}
pub(super) fn uncertain(app: &Shared, s: &Step, reason: &str) {
    if let Some(token) = &s.lease {
        let _ = app.leases.mark_uncertain(token, reason);
    }
}
pub(super) fn completed(app: &Shared, s: &Step) -> Result<(), String> {
    if let Some(token) = &s.lease {
        if app.leases.get(token).is_none() {
            return Err("Stale workspace fencing token".into());
        }
    }
    Ok(())
}
pub(super) fn reconcile(app: &Shared) {
    // Recover the crash window between committing a final step result and releasing its lease.
    let disk = app.work.inner.lock().unwrap();
    for w in &disk.work {
        for s in &w.steps {
            if let Some(token) = &s.lease {
                if app.leases.get(token).is_some() {
                    let evidence = if s.quiescent_proof {
                        match s.status.as_str() {
                            "done" => Some(ReleaseEvidence::Completed),
                            "cancelled" => Some(ReleaseEvidence::StoppedConfirmed),
                            _ => None,
                        }
                    } else {
                        None
                    };
                    if let Some(e) = evidence {
                        let _ = app.leases.release(token, e);
                    }
                }
            }
        }
    }
}
