import { useEffect, useState } from 'react'
import { fetchIncidentSummaries } from './api/incidentClient'
import type { IncidentSummary } from './api/incidents'

type LoadState =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'empty' }
  | { kind: 'success'; incidents: IncidentSummary[] }

function formatUtc(timestamp: string): string {
  return (
    new Date(timestamp)
      .toISOString()
      .slice(0, 19)
      .replace('T', ' ') + ' UTC'
  )
}

function occurrenceLabel(count: number): string {
  return `${count} occurrence${count === 1 ? '' : 's'}`
}

const configuredProjectId = import.meta.env.VITE_PROJECT_ID?.trim()

function App() {
  const [state, setState] = useState<LoadState>(
  configuredProjectId ? { kind: 'loading' } : { kind: 'error' },
  )

  useEffect(() => {
    if (!configuredProjectId) {
        console.error(
          new Error('Frontend configuration is missing VITE_PROJECT_ID'),
        )
        return
      }
    const projectId = configuredProjectId
    const controller = new AbortController()

    async function loadIncidents() {
      try {
        const incidents = await fetchIncidentSummaries(
          projectId,
          controller.signal,
        )

        if (controller.signal.aborted) {
          return
        }

        if (incidents.length === 0) {
          setState({ kind: 'empty' })
          return
        }

        setState({ kind: 'success', incidents })
      } catch (error: unknown) {
        if (
          error instanceof DOMException &&
          error.name === 'AbortError'
        ) {
          return
        }

        console.error('Failed to load incidents', error)
        setState({ kind: 'error' })
      }
    }

    void loadIncidents()

    return () => {
      controller.abort()
    }
  }, [])

  return (
    <main>
      <header>
        <p>TriageHarbor</p>
        <h1>Incidents</h1>
      </header>

      {state.kind === 'loading' && (
        <p role="status">Loading incidents…</p>
      )}

      {state.kind === 'error' && (
        <p role="alert">Failed to load incidents.</p>
      )}

      {state.kind === 'empty' && (
        <p>No incidents yet.</p>
      )}

      {state.kind === 'success' && (
        <ul>
          {state.incidents.map((incident) => (
            <li key={incident.id}>
              <article>
                <header>
                  <h2>{incident.title}</h2>
                  <p>{incident.status}</p>
                </header>

                <p>{occurrenceLabel(incident.occurrenceCount)}</p>

                <dl>
                  <div>
                    <dt>First seen</dt>
                    <dd>{formatUtc(incident.firstSeenAt)}</dd>
                  </div>

                  <div>
                    <dt>Last seen</dt>
                    <dd>{formatUtc(incident.lastSeenAt)}</dd>
                  </div>
                </dl>
              </article>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}

export default App