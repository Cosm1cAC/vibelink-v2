import { NotificationTargetSchema } from '../../../shared/vibelinkNotifications';
type Principal = { issuer: string; edition: 'dev' | 'beta'; userId: string };
export function notificationRoute(raw: unknown, current: Principal | null): string | null {
  const parsed = NotificationTargetSchema.safeParse(raw);
  if (!parsed.success || !current) return null;
  const target = parsed.data;
  if (
    target.issuer !== current.issuer ||
    target.edition !== current.edition ||
    target.userId !== current.userId
  )
    return null;
  return `/hub?${new URLSearchParams({ group: target.groupId, work: target.workId })}`;
}
