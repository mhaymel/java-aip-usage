// Confirms the page was served over HTTP by the local backend rather than
// loaded from a file:// URL.
document.getElementById('origin').textContent = window.location.origin;
