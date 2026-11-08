use super::*;
impl WorkStore {
    pub fn session_work(&self, sid: &str) -> Option<String> {
        self.inner
            .lock()
            .unwrap()
            .work
            .iter()
            .find(|w| {
                w.steps
                    .iter()
                    .any(|s| !sid.is_empty() && s.session_id == sid)
            })
            .map(|w| w.id.clone())
    }
    pub fn charge(&self, id: &str, user: &str) -> Result<crate::model_usage::WorkCharge, String> {
        let w = self.get(id)?;
        if w.owner.user_id != user
            || w.mandate.revoked_at > 0
            || w.archived
            || matches!(w.status.as_str(), "cancelled" | "completed")
        {
            return Err("Work model grant is no longer authorized".into());
        }
        Ok(crate::model_usage::WorkCharge {
            work_id: w.id,
            max_tokens: w.budget.tokens,
            max_requests: w.budget.requests,
        })
    }
    pub fn search(&self, group: &str, query: &str, include_archived: bool) -> Vec<Value> {
        let query = query.to_lowercase();
        self.inner
            .lock()
            .unwrap()
            .work
            .iter()
            .filter(|w| {
                w.group_id == group
                    && (include_archived || !w.archived)
                    && format!("{}\n{}\n{}", w.title, w.text, w.result)
                        .to_lowercase()
                        .contains(&query)
            })
            .map(public)
            .collect()
    }
    pub fn export(&self, group: &str, id: &str, user: &str) -> Result<Value, String> {
        let w = self.get(id)?;
        if w.group_id != group || w.owner.user_id != user {
            return Err("Owned work not found".into());
        }
        if w.archived {
            let path = self
                .file
                .parent()
                .unwrap()
                .join("group_work_archive")
                .join(format!("{}.json", w.id));
            return serde_json::from_slice(&fs::read(path).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string());
        }
        serde_json::to_value(w).map_err(|e| e.to_string())
    }
    pub fn archive(&self, group: &str, id: &str, user: &str) -> Result<Work, String> {
        // Full export is committed before compacting. A crash can leave an extra archive,
        // never evidence/attachments missing from both the live ledger and its archive.
        let mut disk = self.inner.lock().unwrap();
        let w = disk
            .work
            .iter()
            .find(|w| w.id == id && w.group_id == group && w.owner.user_id == user)
            .cloned()
            .ok_or("Owned work not found")?;
        if !matches!(w.status.as_str(), "completed" | "cancelled")
            || w.steps.iter().any(|s| {
                matches!(
                    s.status.as_str(),
                    "dispatching" | "waiting" | "cancelled_unknown"
                )
            })
        {
            return Err("只能归档已结束且执行状态明确的任务".into());
        }
        if w.archived {
            return Ok(w);
        }
        let dir = self.file.parent().unwrap().join("group_work_archive");
        fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let path = dir.join(format!("{}.json", w.id));
        let tmp = path.with_extension("tmp");
        let bytes = serde_json::to_vec(&w).map_err(|e| e.to_string())?;
        let mut file = fs::File::create(&tmp).map_err(|e| e.to_string())?;
        file.write_all(&bytes)
            .and_then(|_| file.sync_all())
            .map_err(|e| e.to_string())?;
        drop(file);
        fs::rename(tmp, path).map_err(|e| e.to_string())?;
        let mut next = disk.clone();
        let compact = next.work.iter_mut().find(|x| x.id == id).unwrap();
        compact.archived = true;
        for i in &mut compact.inputs {
            i.files.clear();
        }
        for s in &mut compact.steps {
            s.prompt.clear();
            s.output.clear();
        }
        let out = compact.clone();
        let tmp = self.file.with_extension("tmp");
        let mut file = fs::File::create(&tmp).map_err(|e| e.to_string())?;
        file.write_all(&serde_json::to_vec(&next).map_err(|e| e.to_string())?)
            .and_then(|_| file.sync_all())
            .map_err(|e| e.to_string())?;
        drop(file);
        fs::rename(tmp, &self.file).map_err(|e| e.to_string())?;
        *disk = next;
        Ok(out)
    }
}
