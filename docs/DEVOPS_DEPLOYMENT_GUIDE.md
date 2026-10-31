# OrderCraft — Complete DevOps Deployment & Live Link Guide

This guide details the complete DevOps setup for **OrderCraft**, including Docker containerization, multi-service orchestration with Docker Compose, automated CI/CD pipelines, and how to generate a **public link accessible on any device worldwide (phones, laptops, tablets)**.

---

## 1. DevOps Architecture Overview

OrderCraft employs an enterprise multi-tier micro-service container architecture:

```text
[ Any Device / Smartphone / PC ]
               │ (Public HTTPS URL via Tunnel or Cloud Domain)
               ▼
   ┌────────────────────────────────────────────────────────┐
   │                    Docker Network                      │
   │                                                        │
   │  ┌──────────────────────┐    ┌──────────────────────┐  │
   │  │   ordercraft-tunnel  │    │  ordercraft-frontend │  │
   │  │ (Cloudflare Tunnel)  │───>│   (Nginx + Angular)  │  │
   │  │                      │    │       Port: 80       │  │
   │  └──────────────────────┘    └──────────┬───────────┘  │
   │                                         │              │
   │                          Reverse Proxy  │ /api/*       │
   │                                         ▼              │
   │                              ┌──────────────────────┐  │
   │                              │  ordercraft-backend  │  │
   │                              │ (Spring Boot REST)   │  │
   │                              │      Port: 8081      │  │
   │                              └──────────┬───────────┘  │
   │                                         │              │
   │                                         │ JDBC:3306    │
   │                                         ▼              │
   │                              ┌──────────────────────┐  │
   │                              │   ordercraft-mysql   │  │
   │                              │   (MySQL 8.4 LTS)    │  │
   │                              │   Persistent Volume  │  │
   │                              └──────────────────────┘  │
   └────────────────────────────────────────────────────────┘
```

### Components & DevOps Tools:
1. **Frontend Container:** Multi-stage build (Node.js &rarr; Nginx Alpine) serving compiled Angular static assets and acting as an API reverse proxy.
2. **Backend Container:** Multi-stage build (Maven &rarr; Eclipse Temurin JRE) running Spring Boot with unprivileged non-root user security.
3. **Database Container:** MySQL 8.4 LTS with health checks, initialization scripts, and persistent Docker volumes.
4. **Public Tunnel:** Built-in Cloudflare Tunnel container providing instant zero-configuration HTTPS access from any smartphone or browser.
5. **CI/CD:** Automated GitHub Actions pipeline (`.github/workflows/ci-cd.yml`) for automated building, linting, unit testing, and Docker validation on every push.

---

## 2. Instant Local Deployment with Docker Compose

Ensure Docker Desktop is running on your machine, then execute:

```bash
# Clone/navigate to project directory
cd OrderCraft

# Build all containers and start services in detached mode
docker compose up --build -d
```

### Checking Running Services:
```bash
docker compose ps
```
All four containers (`ordercraft-mysql`, `ordercraft-backend`, `ordercraft-frontend`, and `ordercraft-public-tunnel`) will transition to `healthy` and `running`.

---

## 3. How to Get a Public Link (Accessible on Any Device)

### Option A: Using the Built-In Cloudflare Tunnel (Zero-Config & Free)
When you run `docker compose up -d`, the `tunnel` container automatically requests a secure public HTTPS link from Cloudflare.

To view your generated live URL:
```bash
docker compose logs tunnel
```
Look for the output line similar to:
```text
+--------------------------------------------------------------------------------------------+
|  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |
|  https://ordercraft-sample-subdomain.trycloudflare.com                                    |
+--------------------------------------------------------------------------------------------+
```
👉 **You can copy this URL, send it to anyone, or open it directly in the browser on your smartphone, tablet, or another computer.** It will load the full OrderCraft portal!

To shut down the tunnel and containers:
```bash
docker compose down
```

---

### Option B: Using `ngrok` (Alternative Instant Public Tunnel)
If you already have `ngrok` installed:
```bash
# Start Docker containers (without tunnel)
docker compose up -d db backend frontend

# Expose port 80 to the internet
ngrok http 80
```
`ngrok` will display a public link (e.g., `https://abc1234.ngrok-free.app`) that you can share with evaluators.

---

### Option C: 1-Click Free Cloud Hosting with Render.com
If you want a permanent link hosted 24/7 on the cloud:

#### Step 1: Set Up Free Cloud MySQL (Takes 1 Minute)
Since Render only provides PostgreSQL natively, pair your Render deployment with a 100% free cloud MySQL database:
- **TiDB Serverless (Recommended — 5 GB Free Forever):**
  1. Visit [https://tidbcloud.com](https://tidbcloud.com) and sign up with GitHub or Google.
  2. Click **Create Cluster** &rarr; select **Serverless (Free)**.
  3. Once created, click **Connect** &rarr; choose **Spring Boot / JDBC**.
  4. Note down the **JDBC URL**, **User**, and **Password**.
- *Alternative:* You can also use [Aiven for MySQL](https://aiven.io) or [Clever Cloud](https://www.clever-cloud.com).

#### Step 2: Push Repository to GitHub
Ensure all latest changes are pushed:
```bash
git add .
git commit -m "fix: render deployment configuration and jdk21 docker compatibility"
git push origin main
```

#### Step 3: Deploy Blueprint on Render
1. Log in to [Render.com](https://render.com).
2. Click **New +** &rarr; **Blueprint**.
3. Select your GitHub repository (`HCL_LMS_PROJECT` or `OrderCraft`).
4. Render will detect [`render.yaml`](../render.yaml) and prompt you for the environment variables:
   - `ORDERCRAFT_DB_URL`: Paste your JDBC MySQL connection URL (e.g., `jdbc:mysql://...:4000/ordercraft?useSSL=true&serverTimezone=UTC`)
   - `ORDERCRAFT_DB_USER`: Your MySQL username
   - `ORDERCRAFT_DB_PASSWORD`: Your MySQL password
5. Click **Apply**. Render will automatically:
   - Build the Java / Spring Boot backend container.
   - Run Flyway migrations on the cloud MySQL database and seed initial demo accounts.
   - Build the Angular frontend container with Nginx reverse proxy.
6. Once deployed, Render will provide your public URLs:
   - Frontend: `https://ordercraft-frontend.onrender.com`
   - Backend API: `https://ordercraft-backend.onrender.com`

---

### Option D: Deploying on a Cloud Virtual Machine (AWS EC2 / DigitalOcean / Azure)
To deploy on a standard Linux VM in the cloud:

1. Launch an Ubuntu 22.04 / 24.04 instance.
2. Install Docker & Docker Compose:
   ```bash
   sudo apt-get update
   sudo apt-get install -y docker.io docker-compose-v2
   sudo usermod -aG docker $USER
   ```
3. Copy the project files to the VM.
4. Run:
   ```bash
   docker compose up -d
   ```
5. Open port 80 in your Cloud Security Group (e.g. AWS EC2 Security Groups). You can now access the project via `http://<YOUR_EC2_PUBLIC_IP>`.

---

## 4. CI/CD Pipeline (GitHub Actions)

The repository includes a ready-to-use pipeline configured in [`.github/workflows/ci-cd.yml`](../.github/workflows/ci-cd.yml):

- **Job 1 (`backend-ci`):** Spins up a transient MySQL service, sets up JDK 21, and executes `mvn clean package` to ensure backend integrity.
- **Job 2 (`frontend-ci`):** Sets up Node.js 22, installs dependencies via `npm ci`, and builds the Angular production bundle.
- **Job 3 (`docker-validation`):** Runs `docker compose config` and executes a dry-run build of all multi-stage Docker images to guarantee deployment readiness.

---

## 5. Useful DevOps Commands

| Action | Command |
|---|---|
| View container status | `docker compose ps` |
| View live logs of all services | `docker compose logs -f` |
| View backend logs only | `docker compose logs -f backend` |
| Restart a specific service | `docker compose restart backend` |
| Rebuild after code change | `docker compose up --build -d` |
| Stop all containers | `docker compose down` |
| Stop & purge database volume | `docker compose down -v` |
