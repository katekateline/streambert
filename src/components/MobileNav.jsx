import {
  HomeIcon,
  SearchIcon,
  HistoryIcon,
  SettingsIcon,
} from "./Icons";

export default function MobileNav({ page, onNavigate, onSearch }) {
  const items = [
    {
      id: "home",
      label: "Home",
      icon: <HomeIcon />,
      onClick: () => onNavigate("home"),
    },
    { id: "search", label: "Search", icon: <SearchIcon />, onClick: onSearch },
    {
      id: "history",
      label: "Library",
      icon: <HistoryIcon />,
      onClick: () => onNavigate("history"),
    },
    {
      id: "settings",
      label: "Settings",
      icon: <SettingsIcon />,
      onClick: () => onNavigate("settings"),
    },
  ];

  return (
    <nav className="mobile-nav" aria-label="Main navigation">
      {items.map((item) => (
        <button
          key={item.id}
          className={`mobile-nav-btn${page === item.id ? " active" : ""}`}
          onClick={item.onClick}
          aria-label={item.label}
        >
          {item.icon}
          <span>{item.label}</span>
        </button>
      ))}
    </nav>
  );
}
