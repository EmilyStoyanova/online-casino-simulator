# Infrastructure

| Directory | Contents | Week |
|---|---|---|
| `docker/` | Production Dockerfile for the Spring Boot backend (multi-stage, non-root, JRE 21) | 12 |
| `deployment/` | Deployment configuration and runbooks for Cloudflare Pages (frontend), Render (backend) and Neon (PostgreSQL), plus a documented portable alternative | 12 |

Local development infrastructure lives in the repository-root `docker-compose.yml` (PostgreSQL).
