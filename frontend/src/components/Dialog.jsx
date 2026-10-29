import { useEffect, useId, useRef } from 'react';
import { motion } from 'motion/react';

/**
 * Native <dialog> as a modal: the browser provides focus trapping, Escape to close and the
 * inert background. Focus returns to the opener when it closes.
 */
export function Dialog({ open, onClose, title, children }) {
  const ref = useRef(null);
  const opener = useRef(null);
  const titleId = useId();

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) {
      opener.current = document.activeElement;
      d.showModal?.();
    } else if (!open && d.open) {
      d.close();
    }
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onClose={() => {
        opener.current?.focus?.();
        onClose();
      }}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      {open && (
        <motion.div initial={{ opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }}>
          <div className="dialog-head">
            <h2 id={titleId}>{title}</h2>
            <button type="button" className="btn btn-small" onClick={onClose}>
              Close
            </button>
          </div>
          {children}
        </motion.div>
      )}
    </dialog>
  );
}
