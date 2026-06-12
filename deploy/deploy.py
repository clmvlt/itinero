#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build, upload et restart de l'API spring-org sur le serveur cible.

Configuration via variables d'environnement, ou un fichier deploy/.env
(non versionne). Voir deploy/.env.example.

    DEPLOY_HOST        hote SSH (defaut 192.168.1.161)
    DEPLOY_PORT        port SSH (defaut 22)
    DEPLOY_USER        utilisateur SSH (defaut user)
    DEPLOY_PASSWORD    mot de passe SSH (OBLIGATOIRE)
    DEPLOY_REMOTE_JAR  chemin distant du jar (defaut /home/user/v2/api.jar)
    DEPLOY_SERVICE     service systemd a redemarrer (defaut api-v2)

Usage:
    python deploy/deploy.py                # build + push + restart
    python deploy/deploy.py --no-build     # reutilise le jar existant
    python deploy/deploy.py --with-tests   # ne saute pas les tests Maven
    python deploy/deploy.py --no-restart   # upload sans restart

Dependance: paramiko  ->  pip install -r deploy/requirements.txt
"""

import argparse
import os
import posixpath
import subprocess
import sys
import time

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARTIFACT = "spring-org-0.1.0.jar"
LOCAL_JAR = os.path.join(PROJECT_ROOT, "target", ARTIFACT)


def load_env_file():
    env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if not os.path.isfile(env_path):
        return
    with open(env_path, "r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


def log(msg):
    print(f"[deploy] {msg}", flush=True)


def fail(msg, code=1):
    print(f"[deploy] ERREUR: {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


def config():
    load_env_file()
    password = os.environ.get("DEPLOY_PASSWORD")
    if not password:
        fail("DEPLOY_PASSWORD non defini. Renseigne-le dans deploy/.env "
             "(voir deploy/.env.example) ou en variable d'environnement.")
    return {
        "host": os.environ.get("DEPLOY_HOST", "192.168.1.161"),
        "port": int(os.environ.get("DEPLOY_PORT", "22")),
        "user": os.environ.get("DEPLOY_USER", "user"),
        "password": password,
        "remote_jar": os.environ.get("DEPLOY_REMOTE_JAR", "/home/user/v2/api.jar"),
        "service": os.environ.get("DEPLOY_SERVICE", "api-v2"),
    }


def build(skip_tests):
    mvn = "mvn.cmd" if os.name == "nt" else "mvn"
    cmd = [mvn, "clean", "package"]
    if skip_tests:
        cmd.append("-DskipTests")
    log("build : " + " ".join(cmd))
    try:
        proc = subprocess.run(cmd, cwd=PROJECT_ROOT)
    except FileNotFoundError:
        fail("Maven introuvable (mvn). Verifie qu'il est dans le PATH.")
    if proc.returncode != 0:
        fail(f"build Maven echoue (code {proc.returncode}).")
    if not os.path.isfile(LOCAL_JAR):
        fail(f"jar introuvable apres build : {LOCAL_JAR}")
    log(f"jar pret : {LOCAL_JAR} ({os.path.getsize(LOCAL_JAR) / (1024 * 1024):.1f} Mo)")


def connect(cfg):
    try:
        import paramiko
    except ImportError:
        fail("module 'paramiko' manquant : pip install -r deploy/requirements.txt")

    log(f"connexion SSH {cfg['user']}@{cfg['host']}:{cfg['port']} ...")
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        client.connect(hostname=cfg["host"], port=cfg["port"], username=cfg["user"],
                       password=cfg["password"], timeout=20,
                       look_for_keys=False, allow_agent=False)
    except Exception as exc:
        fail(f"connexion SSH impossible : {exc}")
    return client


def run_remote(client, cmd, password=None):
    stdin, stdout, stderr = client.exec_command(cmd, get_pty=password is not None)
    if password is not None:
        stdin.write(password + "\n")
        stdin.flush()
    rc = stdout.channel.recv_exit_status()
    out = stdout.read().decode("utf-8", "replace").strip()
    err = stderr.read().decode("utf-8", "replace").strip()
    return rc, out, err


def upload(client, cfg):
    remote_jar = cfg["remote_jar"]
    tmp = remote_jar + ".tmp"
    run_remote(client, f"mkdir -p {posixpath.dirname(remote_jar)}")

    sftp = client.open_sftp()
    size = os.path.getsize(LOCAL_JAR)
    start = time.time()
    last = [0.0]

    def progress(sent, total):
        now = time.time()
        if now - last[0] < 0.5 and sent < total:
            return
        last[0] = now
        pct = sent * 100 // total if total else 100
        print(f"\r[deploy] upload {pct:3d}%  {sent / (1024 * 1024):7.1f} Mo", end="", flush=True)

    log(f"upload -> {tmp}")
    try:
        sftp.put(LOCAL_JAR, tmp, callback=progress)
        print()
        sftp.posix_rename(tmp, remote_jar)
    except Exception as exc:
        print()
        sftp.close()
        fail(f"upload SFTP echoue : {exc}")
    sftp.close()
    log(f"upload termine ({size / (1024 * 1024):.1f} Mo en {time.time() - start:.0f} s) -> {remote_jar}")


def restart(client, cfg):
    service = cfg["service"]
    log(f"restart service : sudo systemctl restart {service}")
    rc, out, err = run_remote(client, f"sudo -S -p '' systemctl restart {service}", password=cfg["password"])
    if rc != 0:
        fail(f"restart echoue (code {rc}) : {err or out}")
    rc, out, err = run_remote(client, f"systemctl is-active {service}")
    state = out or err
    if state != "active":
        fail(f"service {service} non actif apres restart : '{state}'")
    log(f"service {service} : ACTIVE")


def main():
    parser = argparse.ArgumentParser(description="Deploiement de l'API spring-org.")
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--with-tests", action="store_true")
    parser.add_argument("--no-restart", action="store_true")
    args = parser.parse_args()

    cfg = config()

    if args.no_build:
        if not os.path.isfile(LOCAL_JAR):
            fail(f"--no-build mais jar absent : {LOCAL_JAR}")
        log(f"build saute, jar existant : {LOCAL_JAR}")
    else:
        build(skip_tests=not args.with_tests)

    client = connect(cfg)
    try:
        upload(client, cfg)
        if args.no_restart:
            log("restart saute (--no-restart).")
        else:
            restart(client, cfg)
    finally:
        client.close()

    log("deploiement OK.")


if __name__ == "__main__":
    main()
