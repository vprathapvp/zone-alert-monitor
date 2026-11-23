import { useEffect, useRef, useState, type FormEvent } from 'react';

/** Native modal <dialog>: focus is trapped and Escape closes it, as the platform provides. */
export function ResolveDialog({ alertLabel, onCancel, onSubmit }: {
  alertLabel: string;
  onCancel: () => void;
  onSubmit: (note: string) => Promise<void>;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const d = ref.current!;
    d.showModal();
    const onClose = () => onCancel();
    d.addEventListener('cancel', onClose);
    return () => d.removeEventListener('cancel', onClose);
  }, [onCancel]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (note.trim().length === 0) {
      setError('Write a note of 1 to 500 characters.');
      return;
    }
    setBusy(true);
    try {
      await onSubmit(note);
    } finally {
      setBusy(false);
    }
  };

  return (
    <dialog ref={ref} aria-labelledby="resolve-title" className="dialog">
      <form onSubmit={submit}>
        <h2 id="resolve-title">Resolve {alertLabel}</h2>
        <label htmlFor="resolve-note">Resolution note (required, up to 500 characters)</label>
        <textarea id="resolve-note" value={note} maxLength={500} rows={4} autoFocus
          aria-describedby="resolve-count" onChange={(e) => setNote(e.target.value)} />
        <div id="resolve-count" className="muted">{note.length} / 500</div>
        {error && <p role="alert" className="error">{error}</p>}
        <div className="actions">
          <button type="button" onClick={onCancel}>Cancel</button>
          <button type="submit" disabled={busy}>Resolve</button>
        </div>
      </form>
    </dialog>
  );
}
