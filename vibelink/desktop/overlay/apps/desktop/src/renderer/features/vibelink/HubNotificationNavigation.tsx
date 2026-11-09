import { useEffect, useRef } from 'react';
import { useHubAuth } from './useHubAuth';
import { notificationRoute } from './notificationNavigation';
export function HubNotificationNavigation() {
  const opening = useRef(0);
  const { snapshot } = useHubAuth();
  useEffect(() => {
    const bridge = window.electronAPI?.vibelink;
    if (!bridge?.onNotificationOpen) return;
    let active = true;
    const unsubscribe = bridge.onNotificationOpen((target) => {
      if (!active) return;
      const route = notificationRoute(target, snapshot.session);
      if (route) window.location.hash = `${route}&notification=${Date.now()}_${++opening.current}`;
    });
    return () => {
      active = false;
      unsubscribe();
    };
  }, [snapshot.session]);
  return null;
}
