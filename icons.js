(()=>{
const icons={
  "home":`<path d="m3 11 9-8 9 8"/><path d="M5 10v10h5v-6h4v6h5V10"/>`,
  "search":`<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>`,
  "bell":`<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/>`,
  "message-circle":`<path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4Z"/>`,
  "mail":`<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>`,
  "user":`<circle cx="12" cy="8" r="4"/><path d="M4.5 21a7.5 7.5 0 0 1 15 0"/>`,
  "user-plus":`<path d="M15 21a7 7 0 0 0-14 0"/><circle cx="8" cy="7" r="4"/><path d="M19 8v6M16 11h6"/>`,
  "users":`<path d="M16 21a6 6 0 0 0-12 0"/><circle cx="10" cy="8" r="4"/><path d="M18 8a3 3 0 0 1 0 6"/><path d="M20 21a5 5 0 0 0-4-4.58"/>`,
  "bookmark":`<path d="M6 4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18l-6-4-6 4Z"/>`,
  "ellipsis":`<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>`,
  "plus":`<path d="M12 5v14M5 12h14"/>`,
  "image":`<rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="9" cy="10" r="2"/><path d="m4 17 5-4 3 3 3-2 5 4"/>`,
  "video":`<rect x="3" y="6" width="13" height="12" rx="2"/><path d="m16 10 5-3v10l-5-3Z"/>`,
  "chart":`<path d="M5 20V10M12 20V4M19 20v-7"/>`,
  "smile":`<circle cx="12" cy="12" r="9"/><path d="M8 10h.01M16 10h.01M8 15c2.7 2.2 5.3 2.2 8 0"/>`,
  "send":`<path d="m3 11 18-8-7 18-3-7-8-3Z"/><path d="m11 14 4-4"/>`,
  "chevron-left":`<path d="m15 18-6-6 6-6"/>`,
  "chevron-down":`<path d="m6 9 6 6 6-6"/>`,
  "phone":`<path d="M6.6 2.8 9.4 7 7.7 9.1c1.2 2.6 3.5 4.9 6.2 6.2l2.1-1.7 4.2 2.8c.5.3.7.9.5 1.5l-.8 2.6c-.2.7-.9 1.2-1.7 1.2C9.3 21.2 2.8 14.7 2.3 5.8c0-.8.5-1.5 1.2-1.7l2.6-.8c.6-.2 1.2 0 1.5.5Z"/>`,
  "phone-off":`<path d="m3 3 18 18"/><path d="M7.8 7.8 7 9c1.2 2.6 3.5 4.9 6.2 6.2l1.2-.9"/><path d="m16 13.6 4.2 2.8c.5.3.7.9.5 1.5l-.8 2.6c-.2.7-.9 1.2-1.7 1.2-8.9-.5-15.4-7-15.9-15.9 0-.8.5-1.5 1.2-1.7l2.6-.8"/>`,
  "paperclip":`<path d="m8.5 12.5 6.6-6.6a3.2 3.2 0 1 1 4.5 4.5l-8.4 8.4a5.2 5.2 0 0 1-7.4-7.4l8.1-8.1"/>`,
  "mic":`<rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3M9 21h6"/>`,
  "mic-off":`<path d="m3 3 18 18"/><path d="M9 9v2a3 3 0 0 0 5.1 2.1"/><path d="M15 8V6a3 3 0 0 0-5.7-1.3"/><path d="M5 11a7 7 0 0 0 11.4 5.4M19 11a7 7 0 0 1-.5 2.6M12 18v3M9 21h6"/>`,
  "camera":`<path d="M14.5 4 16 6h3a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h3l1.5-2Z"/><circle cx="12" cy="13" r="3.5"/>`,
  "camera-off":`<path d="m3 3 18 18"/><path d="M10.6 4H9.5L8 6H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 1.7-1"/><path d="M16 6h3a2 2 0 0 1 2 2v7"/><path d="M9.5 10.5a3.5 3.5 0 0 0 4 5"/>`,
  "monitor":`<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>`,
  "volume":`<path d="M11 5 6 9H3v6h3l5 4Z"/><path d="M15 9a4 4 0 0 1 0 6M18 6a8 8 0 0 1 0 12"/>`,
  "heart":`<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.6l-1-1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21l7.8-7.6 1-1a5.5 5.5 0 0 0 0-7.8Z"/>`,
  "repeat":`<path d="m17 2 4 4-4 4"/><path d="M3 11V9a3 3 0 0 1 3-3h15"/><path d="m7 22-4-4 4-4"/><path d="M21 13v2a3 3 0 0 1-3 3H3"/>`,
  "share":`<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 10.5 6.8-4M8.6 13.5l6.8 4"/>`,
  "globe":`<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a15 15 0 0 1 0 18"/><path d="M12 3a15 15 0 0 0 0 18"/>`,
  "flame":`<path d="M12 22c4.4 0 8-3.3 8-7.6 0-3.1-1.6-5.6-4.8-8.4.1 2.2-.7 4-2.2 5.1.1-3.5-1.7-6.4-5.2-9.1.2 4.2-3.8 6.5-3.8 11.8C4 18.4 7.6 22 12 22Z"/><path d="M9.7 17.2c0 1.5 1 2.8 2.4 2.8 1.6 0 2.8-1.2 2.8-2.8 0-1.2-.6-2.2-1.8-3.3 0 1-.3 1.7-.9 2.2-.1-1.3-.7-2.5-2-3.5.1 1.7-.5 2.8-.5 4.6Z"/>`,
  "upload":`<path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M5 20h14"/>`,
  "x":`<path d="M18 6 6 18M6 6l12 12"/>`,
  "square":`<rect x="6" y="6" width="12" height="12" rx="1.5"/>`,
  "log-out":`<path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4"/><path d="M14 8l4 4-4 4"/><path d="M18 12H9"/>`
};

function icon(name,extraClass=""){
  const body=icons[name]||icons["ellipsis"];
  return `<svg class="atta-icon ${extraClass}" viewBox="0 0 24 24" aria-hidden="true" focusable="false">${body}</svg>`;
}

function hydrate(root=document){
  root.querySelectorAll("[data-atta-icon]").forEach(el=>{
    const name=el.getAttribute("data-atta-icon");
    const extra=el.getAttribute("data-icon-class")||"";
    el.innerHTML=icon(name,extra);
    el.classList.add("atta-icon-slot");
  });
}

window.AttaIcons={icon,hydrate};
window.attaIcon=icon;
hydrate(document);
})();