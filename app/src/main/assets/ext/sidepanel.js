// Media Grabber - side panel: the same list as the popup, refreshed when you switch or reload a tab
let _t;
const again = () => { clearTimeout(_t); _t = setTimeout(() => location.reload(), 350); };
chrome.tabs.onActivated.addListener(again);
chrome.tabs.onUpdated.addListener((id, ch, tab) => { if (ch.status === 'complete' && tab.active) again(); });
