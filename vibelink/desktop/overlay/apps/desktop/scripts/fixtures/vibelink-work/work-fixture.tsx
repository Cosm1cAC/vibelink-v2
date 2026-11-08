import { mountPage } from '../../ui-page/harness';
import { HubWorkPanel } from '../../../src/renderer/features/vibelink/HubWorkPanel';
const session = {
  userId: 'fixture',
  label: 'Fixture',
  tenantId: 'fixture',
  edition: 'beta' as const,
  sessionId: 'fixture',
  expiresAt: 9999999999999,
  issuer: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  hubId: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  permissions: ['client' as const],
};
mountPage({
  host: (ctx) => ({
    vibelink: {
      enabled: true,
      work: async (input: any) => {
        if (input.action === 'groups')
          return {
            success: true,
            data: {
              kind: 'groups',
              groups:
                ctx.case === 'empty'
                  ? []
                  : [{ id: 'g', name: 'Research & delivery', botIds: ['chief', 'reviewer'] }],
            },
          };
        if (input.action === 'bots')
          return {
            success: true,
            data: {
              kind: 'bots',
              bots: [
                { id: 'chief', name: 'Coordinator', jobTitle: 'Coordinator', archived: false },
              ],
            },
          };
        if (input.action === 'messages')
          return {
            success: true,
            data: {
              kind: 'messages',
              messages: [
                {
                  id: 'm',
                  senderId: 'reviewer',
                  senderName: 'Reviewer',
                  text: 'The shared acceptance checklist is ready.',
                  at: 10000,
                  status: 'done',
                  linkedSessionId: 'agent-session-123',
                },
              ],
            },
          };
        if (input.action === 'list')
          return {
            success: true,
            data: {
              kind: 'list',
              work: [
                {
                  id: 'w',
                  groupId: 'g',
                  title: 'Compare desktop and phone workflows',
                  text: 'Prepare a shared acceptance checklist.',
                  status: ctx.case === 'unknown' ? 'cancelled_unknown' : 'completed',
                  phase: 'delivery',
                  createdAt: 1,
                  updatedAt: 1,
                  error: '',
                  result:
                    'The acceptance checklist is ready. Both clients reflect the same Hub task.',
                  canManage: ctx.case !== 'readonly',
                  retryable: false,
                  archived: false,
                  acceptance: 'A shared checklist',
                  budget: { requests: 20, tokens: 100000 },
                  mandate: {
                    authority: 'explicit_user_request',
                    revokedAt: 0,
                    acceptance: 'A shared acceptance checklist.',
                    members: [
                      {
                        botId: 'reviewer',
                        agent: 'codex',
                        model: 'auto/deepseek-v4.1-flash',
                        device: 'pc',
                        configuredWorkspace: 'project',
                        allowWrite: false,
                      },
                    ],
                  },
                  unread: true,
                  inputs: [],
                  steps: [
                    {
                      id: 's',
                      botId: 'reviewer',
                      kind: 'review',
                      status: 'done',
                      sessionId: 'agent-session-123',
                      output: 'Reviewed the shared checklist.',
                      error: '',
                    },
                  ],
                  billing: { requests: 3, totalTokens: 9420 },
                },
              ],
            },
          };
        return { success: true, data: { kind: 'outbox', outbox: [] } };
      },
    },
  }),
  render: () => (
    <main className="h-screen bg-[var(--surface)] p-6 text-[var(--text-primary)]">
      <HubWorkPanel session={session} bridge={window.electronAPI?.vibelink} />
    </main>
  ),
});
