import type { NotificationHandlers, OSNotificationContent } from './notificationService.js';
interface NativeNotification {
  on(event: string, listener: () => void): unknown;
  show(): void;
  close(): void;
}
export function createOSNotificationAdapter(ports: {
  supported(): boolean;
  registered(): boolean;
  create(content: OSNotificationContent): NativeNotification;
}) {
  return {
    create(content: OSNotificationContent, handlers: NotificationHandlers) {
      if (!/^[a-f0-9]{64}$/.test(content.id) || !/^[a-f0-9]{64}$/.test(content.groupId))
        throw new Error('Invalid bounded OS notification identity');
      if (!ports.supported() || !ports.registered())
        throw new Error('OS notification identity unavailable');
      const notification = ports.create(content);
      notification.on('show', handlers.show);
      notification.on('click', handlers.click);
      notification.on('close', handlers.close);
      notification.on('failed', handlers.error);
      return { show: () => notification.show(), close: () => notification.close() };
    },
  };
}
