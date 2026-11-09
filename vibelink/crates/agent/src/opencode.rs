//! Legacy shared OpenCode service is deliberately inaccessible. Real OpenCode2 runs
//! in cli_runners via `opencode2 run --standalone` with a per-user metered provider.
use std::path::Path;
pub struct Client;
fn retired()->Result<String,String>{Err("Legacy shared OpenCode service retired; use the OpenCode2 CLI runner".into())}
impl Client {
 pub fn ensure(_: &Path)->Result<Self,String>{Err("Legacy shared OpenCode service retired; use the OpenCode2 CLI runner".into())}
 pub fn create(&self,_:&str,_:&str)->Result<String,String>{retired()}
 pub fn list(&self)->Result<String,String>{retired()}
 pub fn active(&self)->Result<String,String>{retired()}
 pub fn messages(&self,_:&str,_:Option<&str>)->Result<String,String>{retired()}
 pub fn prompt(&self,_:&str,_:&str,_:Option<&serde_json::Value>)->Result<String,String>{retired()}
 pub fn interrupt(&self,_:&str)->Result<String,String>{retired()}
}
