#!/usr/bin/env python3
"""Key-only userspace SSH fallback server.

Listens on 127.0.0.1:12222 only; intended to be exposed via ngrok tunnel.
No passwords, no keyboard-interactive auth -- public keys only.
Exec requests use plain pipes; interactive shell uses a real PTY.
"""
import asyncio
import fcntl
import logging
import os
import pty
import struct
import termios

import asyncssh

BASE = os.path.expanduser("~/.config/ssh-fallback")
HOST_KEY = os.path.join(BASE, "host_key")
AUTHORIZED = os.path.join(BASE, "authorized_keys")
LOG = os.path.join(BASE, "server.log")
USER = "kobayashi"
PORT = 12222

logging.basicConfig(
    filename=LOG,
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)

ENV = {
    "PATH": "/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin:/opt/homebrew/bin",
    "HOME": "/Users/kobayashi",
    "USER": USER,
    "LOGNAME": USER,
    "SHELL": "/bin/zsh",
    "LANG": "en_US.UTF-8",
}


class Server(asyncssh.SSHServer):
    def connection_made(self, conn):
        logging.info("connection from %s", conn.get_extra_info("peername"))

    def begin_auth(self, username):
        return True

    def public_key_auth_supported(self):
        return True

    def validate_public_key(self, username, key):
        ok = username == USER
        logging.info(
            "publickey auth user=%s ok=%s fp=%s", username, ok, key.get_fingerprint()
        )
        return ok


async def _drain(writer):
    drain = getattr(writer, "drain", None)
    if drain:
        await drain()


async def run_exec(process, env):
    proc = await asyncio.create_subprocess_shell(
        process.command,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        env=env,
    )

    async def pump_in():
        try:
            while True:
                data = await process.stdin.read(65536)
                if not data:
                    break
                proc.stdin.write(data)
                await proc.stdin.drain()
        except Exception:
            pass
        finally:
            try:
                proc.stdin.close()
            except Exception:
                pass

    async def pump_out(reader, writer):
        try:
            while True:
                data = await reader.read(65536)
                if not data:
                    break
                writer.write(data)
                await _drain(writer)
        except Exception as exc:
            logging.warning("pump_out failed: %r", exc)

    in_task = asyncio.create_task(pump_in())
    out_tasks = [
        asyncio.create_task(pump_out(proc.stdout, process.stdout)),
        asyncio.create_task(pump_out(proc.stderr, process.stderr)),
    ]
    logging.info("exec started: %r", process.command)
    rc = await proc.wait()
    logging.info("exec process exited rc=%s", rc)
    await asyncio.gather(*out_tasks, return_exceptions=True)
    in_task.cancel()
    logging.info("exec %r rc=%s", process.command, rc)
    process.exit(rc)


def set_winsize(fd, width, height):
    try:
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))
    except OSError:
        pass


async def run_shell(process, env):
    loop = asyncio.get_running_loop()
    term_size = process.term_size or (80, 24, 0, 0)
    width, height, _, _ = term_size

    master, slave = pty.openpty()
    if width and height:
        set_winsize(master, width, height)

    env = dict(env)
    env["TERM"] = process.term_type or "xterm-256color"

    proc = await asyncio.create_subprocess_exec(
        "/bin/zsh", "-l",
        stdin=slave, stdout=slave, stderr=slave,
        env=env, start_new_session=True,
    )
    os.close(slave)

    master_eof = asyncio.Event()

    def on_master_readable():
        try:
            data = os.read(master, 65536)
        except OSError:
            data = b""
        if data:
            try:
                process.stdout.write(data)
            except Exception as exc:
                logging.warning("stdout write failed: %r", exc)
        else:
            loop.remove_reader(master)
            master_eof.set()

    loop.add_reader(master, on_master_readable)

    async def pump_input():
        while True:
            try:
                data = await process.stdin.read(65536)
            except asyncssh.TerminalSizeChanged as exc:
                set_winsize(master, exc.width, exc.height)
                continue
            except Exception:
                break
            if not data:
                break
            try:
                os.write(master, data)
            except OSError:
                break

    input_task = asyncio.create_task(pump_input())

    try:
        await master_eof.wait()
        try:
            process.exit(0)
        except Exception:
            pass
    finally:
        input_task.cancel()
        try:
            loop.remove_reader(master)
        except Exception:
            pass
        if proc.returncode is None:
            try:
                proc.terminate()
            except ProcessLookupError:
                pass
        try:
            os.close(master)
        except OSError:
            pass


async def handle_client(process):
    try:
        if process.command:
            await run_exec(process, ENV)
        else:
            await run_shell(process, ENV)
    except Exception as exc:
        logging.exception("handler failed: %r", exc)
        try:
            process.exit(1)
        except Exception:
            pass


async def main():
    await asyncssh.listen(
        "127.0.0.1",
        PORT,
        server_factory=Server,
        server_host_keys=[HOST_KEY],
        authorized_client_keys=AUTHORIZED,
        process_factory=handle_client,
        sftp_factory=asyncssh.SFTPServer,
        allow_scp=True,
        encoding=None,
        login_timeout=20,
    )
    logging.info("listening on 127.0.0.1:%d", PORT)
    await asyncio.get_running_loop().create_future()


if __name__ == "__main__":
    os.makedirs(BASE, exist_ok=True)
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
