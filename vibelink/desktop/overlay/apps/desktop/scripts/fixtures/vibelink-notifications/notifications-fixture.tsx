import { mountPage } from '../../ui-page/harness';
import { NotificationPanel } from '../../../src/renderer/features/vibelink/NotificationPanel';
mountPage({
  render: (ctx) => (
    <main className="min-h-screen bg-[var(--surface)] p-8 text-[var(--text-primary)]">
      <div className="mx-auto max-w-[1080px]">
        <NotificationPanel
          accountKey="fixture"
          bridge={{
            notifications: async () => ({
              success: true,
              data: {
                scope: 'fixture',
                settings: {
                  enabled: !['default', 'unavailable'].includes(ctx.case),
                  preview: false,
                },
                capability: { supported: true, registered: ctx.case !== 'unavailable' },
                baselined: true,
                error: '',
                entries: ['default', 'unavailable'].includes(ctx.case)
                  ? []
                  : [
                      {
                        id: 'fixture-reminder',
                        key: 'g/w/1/completed',
                        groupId: 'g',
                        workId: 'task_example',
                        eventStatus: 'completed',
                        updatedAt: 1,
                        createdAt: 1794153600000,
                        state: ctx.case === 'unknown' ? 'unknown' : 'delivered',
                        attempt: 1,
                        attemptedAt: 0,
                        error: '',
                        receipts:
                          ctx.case === 'unknown' ? [] : [{ type: 'show', at: 1794153600000 }],
                        preview: null,
                      },
                    ],
              },
            }),
          }}
        />
      </div>
    </main>
  ),
});
