# TriageHarbor Frontend

React + TypeScript frontend for the TriageHarbor incident dashboard.

## Local development

The frontend expects the TriageHarbor Spring Boot backend to be running on port `8080`.

Install dependencies:

```bash
npm install
```

Create local configuration:

```bash
cp .env.example .env.local
```

Set the walking-skeleton project ID in `.env.local`:

```text
VITE_PROJECT_ID=<project-uuid>
```

The project ID is not an ingest credential. Do not put the project's ingest key in frontend environment files.

Start the frontend:

```bash
npm run dev
```

Vite serves the frontend locally and proxies requests under `/api` to the Spring Boot backend on `http://localhost:8080`, removing the `/api` prefix before forwarding them.

## Checks

```bash
npm run lint
npm run build
```

## Current scope

The walking-skeleton UI displays incidents for one configured synthetic project and supports explicit loading, empty, error, and success states.

Authentication, project selection, and richer incident interactions are intentionally deferred.
