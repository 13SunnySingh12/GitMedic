import { useEffect, useId, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import { IconCheck, IconChevron } from './Icons.jsx';

/**
 * The Figma filter pill ("Timeframe: All-time") with its option menu, as an accessible
 * select-only combobox: arrow keys move, Enter/Space choose, Escape closes, focus stays put.
 * `options` is [[value, text], ...].
 */
export function FilterSelect({ label, value, options, onChange }) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const root = useRef(null);
  const id = useId();
  const selected = Math.max(
    0,
    options.findIndex(([v]) => v === value),
  );

  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => root.current?.contains(e.target) || setOpen(false);
    document.addEventListener('pointerdown', close);
    return () => document.removeEventListener('pointerdown', close);
  }, [open]);

  function choose(i) {
    setOpen(false);
    if (options[i][0] !== value) onChange(options[i][0]);
  }

  function onKeyDown(e) {
    const last = options.length - 1;
    if (!open) {
      if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) {
        e.preventDefault();
        setActive(selected);
        setOpen(true);
      }
      return;
    }
    const moves = { ArrowDown: active + 1, ArrowUp: active - 1, Home: 0, End: last };
    if (e.key in moves) {
      e.preventDefault();
      setActive(Math.min(last, Math.max(0, moves[e.key])));
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      choose(active);
    } else if (e.key === 'Escape' || e.key === 'Tab') {
      setOpen(false);
    }
  }

  return (
    <div className="filter" ref={root}>
      <button
        type="button"
        role="combobox"
        className="pill"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={`${id}-list`}
        aria-activedescendant={open ? `${id}-${active}` : undefined}
        aria-label={`${label}: ${options[selected][1]}`}
        onClick={() => {
          setActive(selected);
          setOpen((o) => !o);
        }}
        onKeyDown={onKeyDown}
      >
        <span>
          <span className="pill-label">{label}: </span>
          {options[selected][1]}
        </span>
        <IconChevron />
      </button>
      <AnimatePresence>
        {open && (
          <motion.ul
            key="menu"
            id={`${id}-list`}
            role="listbox"
            aria-label={label}
            className="menu"
            initial={{ scale: 0.97, y: -4 }}
            animate={{ scale: 1, y: 0 }}
            exit={{ scale: 0.97, y: -4, transition: { duration: 0.1 } }}
          >
            {options.map(([v, text], i) => (
              <li
                key={v}
                id={`${id}-${i}`}
                role="option"
                aria-selected={i === selected}
                data-active={i === active}
                onPointerEnter={() => setActive(i)}
                onClick={() => choose(i)}
              >
                {text}
                {i === selected && <IconCheck />}
              </li>
            ))}
          </motion.ul>
        )}
      </AnimatePresence>
    </div>
  );
}
