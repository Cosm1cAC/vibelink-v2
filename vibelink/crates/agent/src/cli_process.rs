//! Each native CLI owns a kernel job. No model/tool runs before assignment;
//! closing the Agent or its job handle terminates its entire process tree.
use std::process::{Child, Command};
#[cfg(windows)]
mod platform {
    use super::*;
    use std::os::windows::{io::AsRawHandle, process::CommandExt};
    use windows_sys::Win32::{
        Foundation::{CloseHandle, HANDLE},
        System::JobObjects::*,
    };
    #[link(name="ntdll")]
    unsafe extern "system" {
        fn NtResumeProcess(process: HANDLE) -> i32;
    }
    pub struct Job(HANDLE);
    unsafe impl Send for Job {}
    unsafe impl Sync for Job {}
    impl Drop for Job {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
    impl Job {
        pub fn spawn(cmd: &mut Command) -> Result<(Child, Self), String> {
            unsafe {
                let handle = CreateJobObjectW(std::ptr::null(), std::ptr::null());
                if handle.is_null() {
                    return Err("Cannot create CLI kernel job".into());
                }
                let job = Job(handle);
                let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
                info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
                if SetInformationJobObject(
                    handle,
                    JobObjectExtendedLimitInformation,
                    &info as *const _ as *const _,
                    std::mem::size_of_val(&info) as u32,
                ) == 0
                {
                    return Err("Cannot enforce CLI job lifetime".into());
                }
                cmd.creation_flags(0x08000004);
                let mut child = cmd
                    .spawn()
                    .map_err(|_| "Cannot spawn official CLI process")?;
                if AssignProcessToJobObject(handle, child.as_raw_handle() as HANDLE) == 0
                    || NtResumeProcess(child.as_raw_handle() as HANDLE) < 0
                {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err("Cannot fence CLI descendants before launch".into());
                }
                Ok((child, job))
            }
        }
        pub fn terminate(&self) -> bool {
            unsafe { TerminateJobObject(self.0, 1) != 0 }
        }
        pub fn quiesce(&self) -> bool {
            self.terminate();
            for _ in 0..100 {
                let mut info: JOBOBJECT_BASIC_ACCOUNTING_INFORMATION =
                    unsafe { std::mem::zeroed() };
                let ok = unsafe {
                    QueryInformationJobObject(
                        self.0,
                        JobObjectBasicAccountingInformation,
                        &mut info as *mut _ as *mut _,
                        std::mem::size_of_val(&info) as u32,
                        std::ptr::null_mut(),
                    )
                };
                if ok == 0 {
                    return false;
                }
                if info.ActiveProcesses == 0 {
                    return true;
                }
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            false
        }
    }
}
#[cfg(not(windows))]
mod platform {
    use super::*;
    use std::os::unix::process::CommandExt;
    unsafe extern "C" {
        fn kill(pid: i32, signal: i32) -> i32;
    }
    pub struct Job(i32);
    impl Drop for Job {
        fn drop(&mut self) {
            self.terminate();
        }
    }
    impl Job {
        pub fn spawn(cmd: &mut Command) -> Result<(Child, Self), String> {
            cmd.process_group(0);
            let child = cmd
                .spawn()
                .map_err(|_| "Cannot start official CLI process")?;
            let job = Self(child.id() as i32);
            Ok((child, job))
        }
        pub fn terminate(&self) -> bool {
            unsafe { kill(-self.0, 9) == 0 }
        }
        pub fn quiesce(&self) -> bool {
            self.terminate();
            unsafe { kill(-self.0, 0) != 0 }
        }
    }
}
pub use platform::Job;

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};
    use std::process::Stdio;
    #[test]
    fn one_kernel_job_terminates_descendants_without_killing_another_runner() {
        let mut other = Command::new("cmd.exe");
        other.args(["/c", "ping -n 30 127.0.0.1 >nul"]);
        other.stdout(Stdio::null()).stderr(Stdio::null());
        let (mut other_child, other_job) = Job::spawn(&mut other).unwrap();
        let mut cmd = Command::new("powershell.exe");
        cmd.args(["-NoProfile","-NonInteractive","-Command","$child=Start-Process -FilePath $env:ComSpec -ArgumentList '/c ping -n 30 127.0.0.1 >nul' -PassThru -WindowStyle Hidden;[Console]::Out.WriteLine($child.Id);Start-Sleep -Seconds 30"]);
        cmd.stdout(Stdio::piped()).stderr(Stdio::null());
        let (mut child, job) = Job::spawn(&mut cmd).unwrap();
        let mut line = String::new();
        BufReader::new(child.stdout.take().unwrap())
            .read_line(&mut line)
            .unwrap();
        assert!(line.trim().parse::<u32>().is_ok());
        assert!(job.terminate());
        child.wait().unwrap();
        assert!(job.quiesce());
        assert!(
            other_child.try_wait().unwrap().is_none(),
            "another runner must remain alive"
        );
        other_job.terminate();
        other_child.wait().unwrap();
        assert!(other_job.quiesce());
    }
    #[test]
    fn nonexistent_cli_fails_before_any_model_or_tool_can_run() {
        let mut cmd = Command::new("vibelink-no-such-cli-executable-918273.exe");
        assert!(Job::spawn(&mut cmd).is_err());
    }
}
