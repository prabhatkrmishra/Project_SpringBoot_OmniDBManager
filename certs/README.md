# TLS material

Everything in this directory is **generated locally and never committed** — see
`DEPLOY.md` §10.1 for the openssl commands that produce the CA and server
certificate.

`compose.postgres.yaml` bind-mounts `server.crt`, `server.key` and `ca.crt` into
PostgreSQL, and the whole directory into the TLS bridge (`:129`), which then
optionally picks up `pool.crt` / `pool.key`.

`.gitignore` excludes the contents of this directory. The `.gitkeep` exists so a
fresh clone has the mount point: Docker creates a missing bind-mount source as
root-owned, and PostgreSQL runs as UID 999.
