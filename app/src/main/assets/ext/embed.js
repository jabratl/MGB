// V74i: when a page is shown inside the Library hub (?embed=1) mark it, so it can hide its own title bar. Runs before the page is drawn.
if (/[?&]embed=1/.test(location.search)) document.documentElement.classList.add('embed');
