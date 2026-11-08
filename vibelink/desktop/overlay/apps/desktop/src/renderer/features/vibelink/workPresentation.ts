export function workActions(work: {
  canManage?: boolean;
  status: string;
  retryable: boolean;
  archived: boolean;
}): Array<'cancel' | 'retry' | 'archive'> {
  if (work.canManage !== true || work.archived) return [];
  const actions: Array<'cancel' | 'retry' | 'archive'> = [];
  if (['queued', 'running', 'needs_input'].includes(work.status)) actions.push('cancel');
  if (work.retryable) actions.push('retry');
  if (['completed', 'failed', 'cancelled'].includes(work.status)) actions.push('archive');
  return actions;
}
