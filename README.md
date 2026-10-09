# Intelligent-CV-Matching-AI-Agent-BE

## CI/CD

GitHub Actions is configured in `.github/workflows/ci-cd.yml`.

- Every pull request and push to `main` runs `./mvnw verify` (including the Testcontainers integration test) and produces a JAR artifact.
- A successful push to `main` publishes both an immutable image tagged with the commit SHA and `latest` to GitHub Container Registry (GHCR).
- Deployment is deliberately disabled until a deployment host is configured. Once configured, each successful `main` build pulls the SHA-tagged image and restarts it with Docker Compose.

### Enable production deployment

Create the GitHub **production** environment, then configure the following repository settings:

| Setting | Value |
| --- | --- |
| Environment variable `DEPLOY_HOST` | Hostname or IP of the Docker server |
| Environment variable `DEPLOY_USER` | SSH user on that server |
| Environment variable `DEPLOY_PATH` | Absolute application directory on that server, e.g. `/opt/intelligent-cv` |
| Environment secret `DEPLOY_SSH_KEY` | Private SSH key for `DEPLOY_USER` |

On the server, create `DEPLOY_PATH/.env`. It must contain all runtime Spring secrets and configuration. Do not commit this file. For a private GHCR image, authenticate the server once with a GitHub token that has `read:packages`:

```sh
echo "$GHCR_TOKEN" | docker login ghcr.io -u GITHUB_USERNAME --password-stdin
```

The deployment exposes port `8080` by default. Override it by setting `APP_PORT` in the server's `.env` file. Set any other Spring properties there as standard environment variables.
