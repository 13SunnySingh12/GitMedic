/** 20px line icons. Decorative: the text next to each icon carries the meaning. */
function Icon({ children, size = 20 }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

export const IconRepos = () => (
  <Icon>
    <path d="M5 4.5A1.5 1.5 0 0 1 6.5 3H19v15H6.5A1.5 1.5 0 0 0 5 19.5v-15Z" />
    <path d="M5 19.5A1.5 1.5 0 0 0 6.5 21H19v-3" />
  </Icon>
);

export const IconTrend = () => (
  <Icon>
    <path d="m3 17 6-6 4 4 8-8" />
    <path d="M15 7h6v6" />
  </Icon>
);

export const IconIssues = () => (
  <Icon>
    <circle cx="12" cy="12" r="9" />
    <circle cx="12" cy="12" r="1.5" fill="currentColor" />
  </Icon>
);

export const IconPull = () => (
  <Icon>
    <circle cx="6" cy="5" r="2" />
    <circle cx="6" cy="19" r="2" />
    <circle cx="18" cy="19" r="2" />
    <path d="M6 7v10M18 17V9a3 3 0 0 0-3-3h-4" />
    <path d="m13 4-2 2 2 2" />
  </Icon>
);

export const IconReport = () => (
  <Icon>
    <path d="M6 3h9l4 4v14H6z" />
    <path d="M14 3v5h5M9 13h7M9 17h5" />
  </Icon>
);

export const IconDownload = ({ size = 18 }) => (
  <Icon size={size}>
    <path d="M12 4v11M7 10l5 5 5-5M5 20h14" />
  </Icon>
);

export const IconSync = ({ size = 18 }) => (
  <Icon size={size}>
    <path d="M20 11a8 8 0 0 0-14.5-4.5L4 8M4 13a8 8 0 0 0 14.5 4.5L20 16" />
    <path d="M4 4v4h4M20 20v-4h-4" />
  </Icon>
);

export const IconPlus = ({ size = 18 }) => (
  <Icon size={size}>
    <path d="M12 5v14M5 12h14" />
  </Icon>
);

export const IconSignOut = ({ size = 18 }) => (
  <Icon size={size}>
    <path d="M10 4H5v16h5M15 8l4 4-4 4M19 12H9" />
  </Icon>
);

export const IconChevron = ({ size = 16 }) => (
  <Icon size={size}>
    <path d="m6 9 6 6 6-6" />
  </Icon>
);

export const IconCheck = ({ size = 16 }) => (
  <Icon size={size}>
    <path d="m5 12 5 5 9-10" />
  </Icon>
);
