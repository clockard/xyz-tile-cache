'use strict';

// ── State ─────────────────────────────────────────────────────────────────────

let map;
let activeLayer = null;
let drawInteraction = null;
let drawSource = null;
let highlightSource = null;
let drawActive = false;
let downloadsPollInterval = null;
let pendingBbox = null;
let layerMap = {};
let currentDownloads = [];
let pendingExportBbox = null;
let exportDrawInteraction = null;

// ── Auth state ────────────────────────────────────────────────────────────────

const auth = {
  config: null,             // { mode, issuerUri?, clientId? } from /auth/config
  accessToken: null,
  refreshToken: null,
  expiresAt: 0,
  refreshTimer: null,
  user: null                // { username, isAdmin, roles, groups }
};

const PKCE_STORAGE_KEY = 'xyz-pkce';
const TOKEN_STORAGE_KEY = 'xyz-tokens';
const ADMIN_TOKEN_STORAGE_KEY = 'xyz-admin-token';

// ── DOM refs ──────────────────────────────────────────────────────────────────

let layerSelect, drawBtn, downloadsBtn, uploadGeoTiffBtn, manageLayersBtn;
let downloadsPanel, downloadsList;
let preloadOverlay, bboxDisplay, zoomSlider, zoomValue, preloadNameInput;
let preloadLayersContainer, preloadWarningRow;
let preloadAllowedUsers, preloadAllowedGroups;
let zoomIndicator, attributionEl;
let geoTiffOverlay, geoTiffNameInput, geoTiffFileInput, geoTiffStatus;
let geoTiffAllowedUsers, geoTiffAllowedGroups;
let exportOverlay, exportLayersContainer, exportBboxDisplay, exportMaxZoom, exportStatus;
let exportDrawBtn, exportClearBboxBtn;
let importOverlay, importFileInput, importStatus;
let loginBtn, logoutBtn, userDisplay;
let layerManagerOverlay, layerManagerTitle, lmListView, lmLayerList, lmFormView;
let lfId, lfName, lfSourceType, lfUrl, lfUrlRow, lfWmtsSection;
let lfWmtsLayer, lfWmtsMatrix, lfWmtsStyle, lfWmtsFormat, lfWmtsTime;
let lfAttribution, lfMaxZoom, lfExpiration, lfAllowedUsers, lfAllowedGroups;

let editingLayerName = null;
let lmDeleteConfirm = null;

const VECTOR_LAYER_KEY = '__vector__';
const VECTOR_MAX_ZOOM = 15;

// ── Init ──────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', async () => {
  layerSelect      = document.getElementById('layer-select');
  drawBtn          = document.getElementById('draw-btn');
  downloadsBtn     = document.getElementById('downloads-btn');
  uploadGeoTiffBtn = document.getElementById('upload-geotiff-btn');
  manageLayersBtn  = document.getElementById('manage-layers-btn');
  downloadsPanel = document.getElementById('downloads-panel');
  downloadsList  = document.getElementById('downloads-list');
  preloadOverlay = document.getElementById('preload-overlay');
  bboxDisplay    = document.getElementById('bbox-display');
  zoomSlider     = document.getElementById('zoom-slider');
  zoomValue      = document.getElementById('zoom-value');
  preloadNameInput = document.getElementById('preload-name-input');
  preloadLayersContainer = document.getElementById('preload-layers');
  preloadWarningRow = document.getElementById('preload-warning-row');
  preloadAllowedUsers = document.getElementById('preload-allowed-users');
  preloadAllowedGroups = document.getElementById('preload-allowed-groups');
  zoomIndicator  = document.getElementById('zoom-indicator');
  attributionEl  = document.getElementById('attribution');
  geoTiffOverlay = document.getElementById('geotiff-overlay');
  geoTiffNameInput = document.getElementById('geotiff-name-input');
  geoTiffFileInput = document.getElementById('geotiff-file-input');
  geoTiffStatus = document.getElementById('geotiff-status');
  geoTiffAllowedUsers = document.getElementById('geotiff-allowed-users');
  geoTiffAllowedGroups = document.getElementById('geotiff-allowed-groups');
  exportOverlay = document.getElementById('export-overlay');
  exportLayersContainer = document.getElementById('export-layers');
  exportBboxDisplay = document.getElementById('export-bbox-display');
  exportMaxZoom = document.getElementById('export-max-zoom');
  exportStatus = document.getElementById('export-status');
  exportDrawBtn = document.getElementById('export-draw-btn');
  exportClearBboxBtn = document.getElementById('export-clear-bbox-btn');
  importOverlay = document.getElementById('import-overlay');
  importFileInput = document.getElementById('import-file-input');
  importStatus = document.getElementById('import-status');
  loginBtn = document.getElementById('login-btn');
  logoutBtn = document.getElementById('logout-btn');
  userDisplay = document.getElementById('user-display');

  layerManagerOverlay = document.getElementById('layer-manager-overlay');
  layerManagerTitle   = document.getElementById('layer-manager-title');
  lmListView          = document.getElementById('lm-list-view');
  lmLayerList         = document.getElementById('lm-layer-list');
  lmFormView          = document.getElementById('lm-form-view');
  lfId                = document.getElementById('lf-id');
  lfName              = document.getElementById('lf-name');
  lfSourceType        = document.getElementById('lf-source-type');
  lfUrl               = document.getElementById('lf-url');
  lfUrlRow            = document.getElementById('lf-url-row');
  lfWmtsSection       = document.getElementById('lf-wmts-section');
  lfWmtsLayer         = document.getElementById('lf-wmts-layer');
  lfWmtsMatrix        = document.getElementById('lf-wmts-matrix');
  lfWmtsStyle         = document.getElementById('lf-wmts-style');
  lfWmtsFormat        = document.getElementById('lf-wmts-format');
  lfWmtsTime          = document.getElementById('lf-wmts-time');
  lfAttribution       = document.getElementById('lf-attribution');
  lfMaxZoom           = document.getElementById('lf-max-zoom');
  lfExpiration        = document.getElementById('lf-expiration');
  lfAllowedUsers      = document.getElementById('lf-allowed-users');
  lfAllowedGroups     = document.getElementById('lf-allowed-groups');

  zoomSlider.addEventListener('input', () => {
    zoomValue.textContent = zoomSlider.value;
    updatePreloadEstimates();
  });

  layerSelect.addEventListener('change', () => switchLayer(layerSelect.value));
  drawBtn.addEventListener('click', toggleDraw);
  downloadsBtn.addEventListener('click', toggleDownloadsPanel);
  uploadGeoTiffBtn.addEventListener('click', showGeoTiffModal);
  manageLayersBtn.addEventListener('click', showLayerManager);
  document.getElementById('close-layer-manager').addEventListener('click', hideLayerManager);
  document.getElementById('lm-add-btn').addEventListener('click', showAddLayerForm);
  document.getElementById('lm-done-btn').addEventListener('click', hideLayerManager);
  document.getElementById('lm-save-btn').addEventListener('click', saveLayer);
  document.getElementById('lm-back-btn').addEventListener('click', () => showLayerManagerView('list'));
  lfSourceType.addEventListener('change', onSourceTypeChange);
  layerManagerOverlay.addEventListener('click', (e) => {
    if (e.target === layerManagerOverlay) hideLayerManager();
  });
  document.getElementById('close-downloads').addEventListener('click', closeDownloadsPanel);
  document.getElementById('submit-preload').addEventListener('click', submitPreload);
  document.getElementById('cancel-preload').addEventListener('click', hidePreloadModal);
  document.getElementById('submit-geotiff').addEventListener('click', submitGeoTiff);
  document.getElementById('cancel-geotiff').addEventListener('click', hideGeoTiffModal);
  document.getElementById('export-btn').addEventListener('click', showExportModal);
  document.getElementById('import-btn').addEventListener('click', showImportModal);
  document.getElementById('submit-export').addEventListener('click', submitExport);
  document.getElementById('cancel-export').addEventListener('click', hideExportModal);
  document.getElementById('submit-import').addEventListener('click', submitImport);
  document.getElementById('cancel-import').addEventListener('click', hideImportModal);
  exportDrawBtn.addEventListener('click', startExportDraw);
  exportClearBboxBtn.addEventListener('click', clearExportBbox);
  exportOverlay.addEventListener('click', (e) => {
    if (e.target === exportOverlay) hideExportModal();
  });
  importOverlay.addEventListener('click', (e) => {
    if (e.target === importOverlay) hideImportModal();
  });
  document.getElementById('submit-login').addEventListener('click', submitLogin);
  document.getElementById('cancel-login').addEventListener('click', hideLoginModal);
  document.getElementById('login-overlay').addEventListener('click', (e) => {
    if (e.target.id === 'login-overlay') hideLoginModal();
  });
  document.getElementById('login-token-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitLogin();
  });
  loginBtn.addEventListener('click', login);
  logoutBtn.addEventListener('click', logout);

  downloadsList.addEventListener('click', (e) => {
    const item = e.target.closest('.download-item');
    if (!item) return;
    const idx = parseInt(item.dataset.index, 10);
    const d = currentDownloads[idx];
    document.querySelectorAll('.download-item').forEach(el => el.classList.remove('selected'));
    item.classList.add('selected');
    if (d && Array.isArray(d.bounds) && d.bounds.length === 4) {
      showDownloadBbox(d.bounds);
    }
  });

  preloadOverlay.addEventListener('click', (e) => {
    if (e.target === preloadOverlay) hidePreloadModal();
  });

  geoTiffOverlay.addEventListener('click', (e) => {
    if (e.target === geoTiffOverlay) hideGeoTiffModal();
  });

  initMap();

  await initAuth();
  applyAuthUiState();
  await loadLayers();
});

// ── Auth ──────────────────────────────────────────────────────────────────────

async function initAuth() {
  try {
    const resp = await fetch('/auth/config');
    if (resp.ok) {
      auth.config = await resp.json();
    }
  } catch (e) {
    // No auth config available — run anonymously.
  }

  if (auth.config && auth.config.mode === 'token') {
    const stored = localStorage.getItem(ADMIN_TOKEN_STORAGE_KEY);
    if (stored) {
      auth.accessToken = stored;
      auth.user = { username: 'admin', isAdmin: true, roles: ['admin'], groups: [] };
    }
    return;
  }

  if (await maybeCompleteAuthRedirect()) return;
  loadStoredTokens();
}

async function maybeCompleteAuthRedirect() {
  const params = new URLSearchParams(window.location.search);
  const code = params.get('code');
  const state = params.get('state');
  if (!code || !state) return false;

  const stored = sessionStorage.getItem(PKCE_STORAGE_KEY);
  sessionStorage.removeItem(PKCE_STORAGE_KEY);
  cleanUrlParams();

  if (!stored || !auth.config) return false;
  const pkce = JSON.parse(stored);
  if (pkce.state !== state) {
    showToast('Login failed: state mismatch', 'error');
    return false;
  }

  try {
    const tokens = await exchangeCodeForTokens(code, pkce.codeVerifier, pkce.redirectUri);
    setTokens(tokens);
    return true;
  } catch (e) {
    showToast('Login failed', 'error');
    return false;
  }
}

function cleanUrlParams() {
  const url = new URL(window.location.href);
  ['code', 'state', 'session_state', 'iss'].forEach((p) => url.searchParams.delete(p));
  window.history.replaceState({}, '', url.pathname + (url.search || '') + url.hash);
}

function loadStoredTokens() {
  const raw = localStorage.getItem(TOKEN_STORAGE_KEY);
  if (!raw) return;
  try {
    const t = JSON.parse(raw);
    if (!t.refreshToken) return;
    if (t.expiresAt > Date.now() + 5000) {
      setTokensFromState(t);
    } else {
      refreshTokens().catch(() => clearTokens());
    }
  } catch (e) {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
  }
}

async function login() {
  if (auth.config && auth.config.mode === 'token') {
    showLoginModal();
    return;
  }
  if (!auth.config || !auth.config.issuerUri) {
    showToast('Auth is not configured on this server', 'error');
    return;
  }
  const codeVerifier = randomString(64);
  const codeChallenge = await sha256Base64Url(codeVerifier);
  const state = randomString(32);
  const redirectUri = window.location.origin + '/';

  sessionStorage.setItem(PKCE_STORAGE_KEY, JSON.stringify({
    codeVerifier, state, redirectUri
  }));

  const params = new URLSearchParams({
    client_id: auth.config.clientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'openid profile',
    code_challenge: codeChallenge,
    code_challenge_method: 'S256',
    state
  });
  window.location.href = `${auth.config.issuerUri}/protocol/openid-connect/auth?${params}`;
}

async function logout() {
  if (auth.config && auth.config.mode === 'token') {
    localStorage.removeItem(ADMIN_TOKEN_STORAGE_KEY);
    auth.accessToken = null;
    auth.user = null;
    applyAuthUiState();
    await loadLayers();
    return;
  }

  const idToken = auth.idToken;
  clearTokens();
  applyAuthUiState();
  await loadLayers();

  if (auth.config && auth.config.issuerUri) {
    const params = new URLSearchParams({
      post_logout_redirect_uri: window.location.origin + '/',
      client_id: auth.config.clientId
    });
    if (idToken) params.set('id_token_hint', idToken);
    window.location.href =
      `${auth.config.issuerUri}/protocol/openid-connect/logout?${params}`;
  }
}

function showLoginModal() {
  const overlay = document.getElementById('login-overlay');
  const input = document.getElementById('login-token-input');
  const status = document.getElementById('login-status');
  input.value = '';
  status.textContent = '';
  overlay.classList.remove('hidden');
  setTimeout(() => input.focus(), 0);
}

function hideLoginModal() {
  document.getElementById('login-overlay').classList.add('hidden');
}

async function submitLogin() {
  const input = document.getElementById('login-token-input');
  const status = document.getElementById('login-status');
  const token = input.value.trim();
  if (!token) {
    status.textContent = 'Token required.';
    return;
  }
  status.textContent = 'Verifying…';
  try {
    const probe = await fetch('/preloads', {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (probe.status === 401 || probe.status === 403) {
      status.textContent = 'Invalid token.';
      return;
    }
  } catch (e) {
    status.textContent = 'Network error.';
    return;
  }
  localStorage.setItem(ADMIN_TOKEN_STORAGE_KEY, token);
  auth.accessToken = token;
  auth.user = { username: 'admin', isAdmin: true, roles: ['admin'], groups: [] };
  hideLoginModal();
  applyAuthUiState();
  await loadLayers();
}

async function exchangeCodeForTokens(code, codeVerifier, redirectUri) {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: auth.config.clientId,
    code,
    redirect_uri: redirectUri,
    code_verifier: codeVerifier
  });
  const resp = await fetch(`${auth.config.issuerUri}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body
  });
  if (!resp.ok) throw new Error(`Token exchange failed: ${resp.status}`);
  return resp.json();
}

async function refreshTokens() {
  if (!auth.refreshToken || !auth.config) throw new Error('No refresh token');
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: auth.config.clientId,
    refresh_token: auth.refreshToken
  });
  const resp = await fetch(`${auth.config.issuerUri}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body
  });
  if (!resp.ok) throw new Error(`Refresh failed: ${resp.status}`);
  setTokens(await resp.json());
}

function setTokens(tokenResponse) {
  const expiresAt = Date.now() + (tokenResponse.expires_in || 60) * 1000;
  setTokensFromState({
    accessToken: tokenResponse.access_token,
    refreshToken: tokenResponse.refresh_token,
    idToken: tokenResponse.id_token,
    expiresAt
  });
}

function setTokensFromState(t) {
  auth.accessToken = t.accessToken;
  auth.refreshToken = t.refreshToken;
  auth.idToken = t.idToken;
  auth.expiresAt = t.expiresAt;
  auth.user = decodeUser(t.accessToken);
  localStorage.setItem(TOKEN_STORAGE_KEY, JSON.stringify(t));
  scheduleRefresh();
}

function clearTokens() {
  auth.accessToken = null;
  auth.refreshToken = null;
  auth.idToken = null;
  auth.expiresAt = 0;
  auth.user = null;
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  if (auth.refreshTimer) {
    clearTimeout(auth.refreshTimer);
    auth.refreshTimer = null;
  }
}

function scheduleRefresh() {
  if (auth.refreshTimer) clearTimeout(auth.refreshTimer);
  const delay = Math.max(5_000, auth.expiresAt - Date.now() - 30_000);
  auth.refreshTimer = setTimeout(() => {
    refreshTokens().catch(() => {
      clearTokens();
      applyAuthUiState();
      loadLayers();
    });
  }, delay);
}

function decodeUser(accessToken) {
  if (!accessToken) return null;
  try {
    const payload = JSON.parse(atob(accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    const roles = (payload.realm_access && payload.realm_access.roles) || [];
    const groups = payload.groups || [];
    return {
      username: payload.preferred_username || payload.sub,
      roles,
      groups,
      isAdmin: roles.includes('admin')
    };
  } catch (e) {
    return null;
  }
}

function isLoggedIn() {
  return !!auth.accessToken;
}

function isAdmin() {
  return !!(auth.user && auth.user.isAdmin);
}

function applyAuthUiState() {
  const loggedIn = isLoggedIn();
  loginBtn.classList.toggle('hidden', loggedIn);
  logoutBtn.classList.toggle('hidden', !loggedIn);
  if (loggedIn) {
    const tag = isAdmin() ? '<span class="role-tag">Admin</span>' : '';
    userDisplay.innerHTML = `${escapeHtml(auth.user.username)}${tag}`;
    userDisplay.classList.remove('hidden');
  } else {
    userDisplay.classList.add('hidden');
    userDisplay.textContent = '';
  }
  document.querySelectorAll('.admin-only').forEach((el) => {
    el.classList.toggle('hidden', !isAdmin());
  });
  document.querySelectorAll('.auth-only').forEach((el) => {
    el.classList.toggle('hidden', !loggedIn);
  });
}

async function authFetch(input, init = {}) {
  const headers = new Headers(init.headers || {});
  if (auth.accessToken) {
    if (auth.expiresAt - Date.now() < 5_000 && auth.refreshToken) {
      try { await refreshTokens(); } catch (e) { clearTokens(); applyAuthUiState(); }
    }
    if (auth.accessToken) headers.set('Authorization', `Bearer ${auth.accessToken}`);
  }
  return fetch(input, { ...init, headers });
}

// ── PKCE helpers ──────────────────────────────────────────────────────────────

function randomString(len) {
  const bytes = new Uint8Array(len);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => ('0' + (b & 0xff).toString(16)).slice(-2)).join('').slice(0, len);
}

async function sha256Base64Url(str) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(str));
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// ── Map ───────────────────────────────────────────────────────────────────────

function initMap() {
  drawSource = new ol.source.Vector();
  highlightSource = new ol.source.Vector();

  const highlightLayer = new ol.layer.Vector({
    source: highlightSource,
    style: new ol.style.Style({
      stroke: new ol.style.Stroke({ color: '#4a6fa5', width: 2, lineDash: [6, 3] }),
      fill: new ol.style.Fill({ color: 'rgba(74, 111, 165, 0.12)' })
    }),
    zIndex: 40
  });

  const drawOverlay = new ol.layer.Vector({
    source: drawSource,
    style: new ol.style.Style({
      stroke: new ol.style.Stroke({ color: '#f90', width: 2 }),
      fill: new ol.style.Fill({ color: 'rgba(255, 153, 0, 0.1)' })
    }),
    zIndex: 50
  });

  map = new ol.Map({
    target: 'map',
    controls: [
      new ol.control.Zoom(),
      new ol.control.ScaleLine({ units: 'metric' })
    ],
    layers: [highlightLayer, drawOverlay],
    view: new ol.View({
      center: ol.proj.fromLonLat([0, 20]),
      zoom: 2
    })
  });

  map.getView().on('change:resolution', updateZoomIndicator);
  updateZoomIndicator();
}

function updateZoomIndicator() {
  const z = map.getView().getZoom();
  if (zoomIndicator) zoomIndicator.textContent = `Z ${z != null ? z.toFixed(1) : '–'}`;
}

function setMapTileLayer(newLayer) {
  const layers = map.getLayers();
  if (activeLayer) {
    const idx = layers.getArray().indexOf(activeLayer);
    if (idx >= 0) {
      layers.setAt(idx, newLayer);
    } else {
      layers.insertAt(0, newLayer);
    }
  } else {
    layers.insertAt(0, newLayer);
  }
  activeLayer = newLayer;
}

function updateAttribution(html) {
  if (attributionEl) attributionEl.innerHTML = html || '';
}

function showDownloadBbox(bounds) {
  if (!highlightSource) return;
  highlightSource.clear();
  const [minLon, minLat, maxLon, maxLat] = bounds;
  const ring = [
    ol.proj.fromLonLat([minLon, minLat]),
    ol.proj.fromLonLat([maxLon, minLat]),
    ol.proj.fromLonLat([maxLon, maxLat]),
    ol.proj.fromLonLat([minLon, maxLat]),
    ol.proj.fromLonLat([minLon, minLat])
  ];
  const feature = new ol.Feature(new ol.geom.Polygon([ring]));
  highlightSource.addFeature(feature);
  map.getView().fit(feature.getGeometry(), { padding: [60, 60, 60, 60], duration: 400, maxZoom: 12 });
}

// ── Tile loaders that pass auth tokens ────────────────────────────────────────

function authTileLoadFunction(imageTile, src) {
  if (!auth.accessToken) {
    imageTile.getImage().src = src;
    return;
  }
  fetch(src, { headers: { Authorization: `Bearer ${auth.accessToken}` } })
    .then((r) => r.ok ? r.blob() : Promise.reject(new Error(`HTTP ${r.status}`)))
    .then((blob) => {
      const url = URL.createObjectURL(blob);
      const img = imageTile.getImage();
      img.onload = img.onerror = () => URL.revokeObjectURL(url);
      img.src = url;
    })
    .catch(() => imageTile.getImage().src = '');
}

function authVectorTileLoader(tile, url) {
  tile.setLoader(async (extent, resolution, projection) => {
    try {
      const headers = auth.accessToken
        ? { Authorization: `Bearer ${auth.accessToken}` }
        : {};
      const resp = await fetch(url, { headers });
      if (!resp.ok) {
        tile.setFeatures([]);
        return;
      }
      const buf = await resp.arrayBuffer();
      const format = tile.getFormat();
      tile.setFeatures(format.readFeatures(buf, { extent, featureProjection: projection }));
    } catch (e) {
      tile.setFeatures([]);
    }
  });
}

// ── Layer loading ─────────────────────────────────────────────────────────────

async function loadLayers() {
  const previous = layerSelect.value;
  layerSelect.innerHTML = '';
  layerMap = {};

  let layers = [];
  try {
    const resp = await authFetch('/layers');
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    layers = await resp.json();
  } catch (e) {
    showToast('Failed to load layers', 'error');
  }

  layers.forEach((layer) => {
    const key = layer.id || layer.name;
    layerMap[key] = layer;
    const opt = document.createElement('option');
    opt.value = key;
    opt.textContent = layer.name || key;
    layerSelect.appendChild(opt);
  });

  const vectorOpt = document.createElement('option');
  vectorOpt.value = '__vector__';
  vectorOpt.textContent = 'OSM Vector';
  layerSelect.appendChild(vectorOpt);

  if (previous && (layerMap[previous] || previous === '__vector__')) {
    layerSelect.value = previous;
    switchLayer(previous);
  } else if (layers.length > 0) {
    const firstId = layers[0].id || layers[0].name;
    layerSelect.value = firstId;
    switchLayer(firstId);
  } else {
    layerSelect.value = '__vector__';
    switchLayer('__vector__');
  }
}

function switchLayer(name) {
  if (name === '__vector__') {
    switchToVector();
    return;
  }

  const layer = layerMap[name] || {};
  setMapTileLayer(
    new ol.layer.Tile({
      source: new ol.source.XYZ({
        url: `/tilesZYX/${encodeURIComponent(name)}/{z}/{y}/{x}.png`,
        crossOrigin: 'anonymous',
        tileLoadFunction: authTileLoadFunction
      })
    })
  );
  updateAttribution(layer.attribution || '');
}

function switchToVector() {
  const source = new ol.source.VectorTile({
    url: '/vector/{z}/{x}/{y}',
    format: new ol.format.MVT(),
    maxZoom: 15
  });
  source.setTileLoadFunction(authVectorTileLoader);
  setMapTileLayer(
    new ol.layer.VectorTile({
      declutter: true,
      source,
      style: vectorStyleFn
    })
  );
  updateAttribution('© <a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org">OpenStreetMap</a>');
}

// ── Vector tile style (Protomaps LIGHT theme) ─────────────────────────────────

const EARTH_STYLE = new ol.style.Style({ fill: new ol.style.Fill({ color: '#e2dfda' }), zIndex: 1 });

const WATER_STYLE = new ol.style.Style({
  fill: new ol.style.Fill({ color: '#80deea' }),
  zIndex: 3
});

const TRANSIT_RAIL = new ol.style.Style({
  stroke: new ol.style.Stroke({ color: '#a7b1b3', width: 1.5 }),
  zIndex: 6
});
const TRANSIT_OTHER = new ol.style.Style({
  stroke: new ol.style.Stroke({ color: '#a7b1b3', width: 1, lineDash: [6, 4] }),
  zIndex: 6
});

const BOUNDARY_COUNTRY = new ol.style.Style({
  stroke: new ol.style.Stroke({ color: '#adadad', width: 1.5, lineDash: [8, 4] }),
  zIndex: 7
});
const BOUNDARY_REGION = new ol.style.Style({
  stroke: new ol.style.Stroke({ color: '#adadad', width: 1, lineDash: [8, 6] }),
  zIndex: 7
});

const LANDUSE_COLORS = {
  park: '#9cd3b4',            grass: '#9cd3b4',            meadow: '#9cd3b4',
  village_green: '#9cd3b4',   recreation_ground: '#9cd3b4', garden: '#9cd3b4',
  national_park: '#9cd3b4',   protected_area: '#9cd3b4',   nature_reserve: '#9cd3b4',
  golf_course: '#9cd3b4',     cemetery: '#cfddd5',
  forest: '#a0d9a0',          wood: '#a0d9a0',
  scrub: '#99d2bb',
  farmland: '#e2dfda',        farm: '#e2dfda',             allotments: '#cfddd5',
  farmyard: '#e2dfda',
  industrial: '#d1dde1',      commercial: '#e2dfda',       retail: '#e2dfda',
  residential: '#e3e0d4',     pedestrian: '#e3e0d4',
  school: '#e4ded7',          college: '#e4ded7',          university: '#e4ded7',
  hospital: '#e4dad9',        clinic: '#e4dad9',
  beach: '#e8e4d0',           sand: '#e8e4d0',
  wetland: '#9cd3b4',         marsh: '#9cd3b4',
  parking: '#e0e0e0',         aerodrome: '#dadbdf',        military: '#dcdcdc',
  zoo: '#c6dcdc'
};

// Colors match OSM Carto standard stylesheet
const ROAD_SPECS = {
  highway:       { fill: '#e892a2', case_: '#c0516b', base: 6 },
  motorway:      { fill: '#e892a2', case_: '#c0516b', base: 6 },
  motorway_link: { fill: '#e892a2', case_: '#c0516b', base: 4 },
  trunk:         { fill: '#f9b29c', case_: '#c84816', base: 5 },
  trunk_link:    { fill: '#f9b29c', case_: '#c84816', base: 3.5 },
  primary:       { fill: '#fcd966', case_: '#a06b00', base: 4.5 },
  primary_link:  { fill: '#fcd966', case_: '#a06b00', base: 3 },
  major_road:    { fill: '#fcd966', case_: '#a06b00', base: 4.5 },
  secondary:     { fill: '#f7fabf', case_: '#707d05', base: 3.5 },
  secondary_link:{ fill: '#f7fabf', case_: '#707d05', base: 2.5 },
  tertiary:      { fill: '#ffffff', case_: '#8f8f8f', base: 3 },
  tertiary_link: { fill: '#ffffff', case_: '#8f8f8f', base: 2 },
  minor_road:    { fill: '#ffffff', case_: '#c0c0c0', base: 2 },
  residential:   { fill: '#ffffff', case_: '#c0c0c0', base: 2 },
  service:       { fill: '#f5f5f5', case_: '#d0d0d0', base: 1 },
  track:         { fill: '#a0a0a0', case_: null, base: 1.5, dash: [6, 3] },
  path:          { fill: '#a0a0a0', case_: null, base: 1,   dash: [4, 3] },
  footway:       { fill: '#a0a0a0', case_: null, base: 1,   dash: [4, 2] },
  cycleway:      { fill: '#6699cc', case_: null, base: 1,   dash: [4, 4] },
  ferry:         { fill: '#80deea', case_: null, base: 1.5, dash: [12, 6] }
};

const MAIN_ROAD_KINDS = new Set(['highway', 'motorway', 'trunk', 'major_road', 'primary']);
const HIGH_ZOOM_KINDS = new Set(['path', 'footway', 'cycleway', 'service', 'residential']);

// zIndex bands: cases 10-17, fills 20-27, labels 30-37
const ROAD_IMPORTANCE = {
  highway: 7, motorway: 7,
  trunk: 6,   major_road: 6,
  motorway_link: 5, trunk_link: 5, primary: 5,
  primary_link: 4,
  secondary: 3, secondary_link: 3,
  tertiary: 2, tertiary_link: 2,
  minor_road: 1, residential: 1,
  service: 0, track: 0, path: 0, footway: 0, cycleway: 0, ferry: 0
};

function roadStyles(kind, z) {
  if (z < 5 && !MAIN_ROAD_KINDS.has(kind)) return null;
  if (z < 7 && !MAIN_ROAD_KINDS.has(kind) && kind !== 'secondary') return null;
  if (z < 10 && HIGH_ZOOM_KINDS.has(kind)) return null;

  const spec = ROAD_SPECS[kind] || ROAD_SPECS['minor_road'];
  const scale = z >= 15 ? 2.0 : z >= 14 ? 1.4 : z >= 12 ? 0.9 : z >= 10 ? 0.55 : z >= 8 ? 0.4 : z >= 6 ? 0.3 : 0.2;
  const fillWidth = spec.base * scale;
  const importance = ROAD_IMPORTANCE[kind] ?? 0;

  if (spec.dash) {
    return new ol.style.Style({
      stroke: new ol.style.Stroke({ color: spec.fill, width: fillWidth, lineDash: spec.dash }),
      zIndex: 20 + importance
    });
  }

  const caseWidth = fillWidth + (z >= 12 ? 2.5 : 1.5);
  return [
    new ol.style.Style({ stroke: new ol.style.Stroke({ color: spec.case_, width: caseWidth }), zIndex: 10 + importance }),
    new ol.style.Style({ stroke: new ol.style.Stroke({ color: spec.fill, width: fillWidth }), zIndex: 20 + importance })
  ];
}

function getLabel(feature) {
  return feature.get('name:en') || feature.get('name');
}

// Protomaps provides kind (broad: 'highway','major_road','minor_road') and
// kind_detail (specific: 'motorway','trunk','primary'...). Check both so the
// thresholds work regardless of which field carries the road classification.
function roadLabelMinZoom(kind, kindDetail) {
  if (HIGH_ZOOM_KINDS.has(kind) || HIGH_ZOOM_KINDS.has(kindDetail)) return 14;
  if (MAIN_ROAD_KINDS.has(kind) || MAIN_ROAD_KINDS.has(kindDetail)) return 8;
  if (kind === 'secondary' || kindDetail === 'secondary' ||
      kind === 'secondary_link' || kindDetail === 'secondary_link') return 10;
  return 12;
}

function roadNameStyle(feature, z) {
  const name = getLabel(feature);
  if (!name) return null;
  const kind = feature.get('kind') || '';
  const kindDetail = feature.get('kind_detail') || '';
  if (z < roadLabelMinZoom(kind, kindDetail)) return null;
  const importance = ROAD_IMPORTANCE[kind] ?? ROAD_IMPORTANCE[kindDetail] ?? 0;
  const fontSize = z >= 14 ? 11 : 10;
  return new ol.style.Style({
    text: new ol.style.Text({
      text: name,
      font: `${fontSize}px Arial, sans-serif`,
      fill: new ol.style.Fill({ color: '#555' }),
      stroke: new ol.style.Stroke({ color: '#ffffff', width: 3 }),
      placement: 'line',
      overflow: false
    }),
    zIndex: 30 + importance
  });
}

// Route number shields (ref property). Protomaps includes ref on highway features
// at low zoom levels, so shields can appear from z6 for major roads.
// Uses backgroundFill/backgroundStroke to create a badge appearance.
function roadShieldStyle(feature, z, kind, kindDetail) {
  const ref = feature.get('ref');
  if (!ref) return null;
  const isMain = MAIN_ROAD_KINDS.has(kind) || MAIN_ROAD_KINDS.has(kindDetail);
  const isTrunkish = kind === 'trunk' || kindDetail === 'trunk' ||
                     kind === 'highway' || kindDetail === 'motorway';
  if (isMain && z < 6) return null;
  if (!isMain && z < 10) return null;

  const spec = ROAD_SPECS[kindDetail] || ROAD_SPECS[kind] || ROAD_SPECS['minor_road'];
  // Pick text color: dark on light fills (yellow/white), light on dark fills
  const textColor = (spec.fill === '#e892a2' || spec.fill === '#f9b29c') ? '#ffffff' : '#333333';

  return new ol.style.Style({
    text: new ol.style.Text({
      text: String(ref),
      font: `bold ${z >= 10 ? 10 : 9}px Arial, sans-serif`,
      fill: new ol.style.Fill({ color: textColor }),
      backgroundFill: new ol.style.Fill({ color: spec.fill }),
      backgroundStroke: new ol.style.Stroke({ color: spec.case_ || '#999', width: 1 }),
      padding: [1, 3, 1, 3],
      placement: 'point',
      overflow: true
    }),
    zIndex: 38 + (ROAD_IMPORTANCE[kind] ?? ROAD_IMPORTANCE[kindDetail] ?? 0)
  });
}

const PLACE_SPECS = {
  continent:     { minZ: 0,  maxSize: 16, minSize: 13, weight: '700', color: '#a3a3a3', upper: true },
  macroregion:   { minZ: 2,  maxSize: 14, minSize: 12, weight: '600', color: '#b3b3b3', upper: true },
  country:       { minZ: 1,  maxSize: 18, minSize: 13, weight: '700', color: '#a3a3a3', upper: true },
  region:        { minZ: 4,  maxSize: 15, minSize: 12, weight: '600', color: '#b3b3b3', upper: true },
  state:         { minZ: 4,  maxSize: 15, minSize: 12, weight: '600', color: '#b3b3b3', upper: true },
  province:      { minZ: 4,  maxSize: 15, minSize: 12, weight: '600', color: '#b3b3b3', upper: true },
  county:        { minZ: 7,  maxSize: 13, minSize: 11, weight: '500', color: '#999999', upper: true },
  macrocounty:   { minZ: 6,  maxSize: 13, minSize: 11, weight: '500', color: '#b3b3b3', upper: true },
  city:          { minZ: 3,  maxSize: 18, minSize: 14, weight: '700', color: '#5c5c5c' },
  town:          { minZ: 8,  maxSize: 14, minSize: 12, weight: '500', color: '#5c5c5c' },
  village:       { minZ: 11, maxSize: 12, minSize: 11, weight: '400', color: '#5c5c5c' },
  hamlet:        { minZ: 12, maxSize: 11, minSize: 10, weight: '400', color: '#7c7c7c' },
  suburb:        { minZ: 12, maxSize: 12, minSize: 11, weight: '400', color: '#8f8f8f' },
  neighborhood:  { minZ: 13, maxSize: 11, minSize: 10, weight: '400', color: '#8f8f8f' },
  neighbourhood: { minZ: 13, maxSize: 11, minSize: 10, weight: '400', color: '#8f8f8f' },
  locality:      { minZ: 3,  maxSize: 16, minSize: 10, weight: '400', color: '#5c5c5c' }
};

// Protomaps encodes all settlements (cities, towns, villages) as kind=locality and
// uses pmap:min_zoom per-feature to distinguish importance (2=major city, 12=hamlet).
// Tiles are already filtered by zoom, so defaulting to 2 is safe — at z5 only major
// cities are present in the tile data at all.
function localityStyle(feature, z, name) {
  const featureMinZ = feature.get('pmap:min_zoom') ?? feature.get('min_zoom') ?? 2;
  if (z < featureMinZ) return null;
  const importance = Math.max(0, 12 - featureMinZ);
  const fontSize = Math.round(10 + importance * 0.55 + Math.min(1, (z - featureMinZ) / 4) * 3);
  const weight = featureMinZ <= 4 ? '700' : featureMinZ <= 7 ? '600' : featureMinZ <= 9 ? '500' : '400';
  const color = featureMinZ <= 7 ? '#4a4a4a' : '#5c5c5c';
  return new ol.style.Style({
    text: new ol.style.Text({
      text: name,
      font: `${weight} ${fontSize}px Arial, sans-serif`,
      fill: new ol.style.Fill({ color }),
      stroke: new ol.style.Stroke({ color: '#e0e0e0', width: 3 }),
      overflow: true,
      placement: 'point'
    })
  });
}

function placeStyle(feature, z) {
  const name = getLabel(feature);
  if (!name) return null;
  const kind = feature.get('kind') || '';

  // Protomaps uses locality for all settlements; delegate to per-feature zoom
  if (kind === 'locality' || kind === 'neighbourhood' || kind === 'neighborhood') {
    return localityStyle(feature, z, name);
  }

  const spec = PLACE_SPECS[kind];
  if (!spec) return null;
  if (z < spec.minZ) return null;

  const t = Math.min(1, (z - spec.minZ) / 4);
  const fontSize = Math.round(spec.minSize + t * (spec.maxSize - spec.minSize));

  return new ol.style.Style({
    text: new ol.style.Text({
      text: spec.upper ? name.toUpperCase() : name,
      font: `${spec.weight} ${fontSize}px Arial, sans-serif`,
      fill: new ol.style.Fill({ color: spec.color }),
      stroke: new ol.style.Stroke({ color: '#e0e0e0', width: 3 }),
      overflow: true,
      placement: 'point'
    })
  });
}

function buildingStyle(feature, z) {
  if (z < 13) return null;
  const alpha = Math.min(1, 0.55 + (z - 13) * 0.25).toFixed(2);
  const styles = [
    new ol.style.Style({
      fill: new ol.style.Fill({ color: `rgba(210,200,185,${alpha})` }),
      stroke: new ol.style.Stroke({ color: `rgba(120,105,90,${alpha})`, width: 1 }),
      zIndex: 8
    })
  ];
  if (z >= 15) {
    const name = getLabel(feature);
    if (name) {
      styles.push(new ol.style.Style({
        text: new ol.style.Text({
          text: name,
          font: '10px Arial, sans-serif',
          fill: new ol.style.Fill({ color: '#555' }),
          stroke: new ol.style.Stroke({ color: 'rgba(255,255,255,0.85)', width: 2 }),
          overflow: true
        })
      }));
    }
  }
  if (z >= 17) {
    const hnum = feature.get('housenumber') || feature.get('addr:housenumber');
    if (hnum) {
      styles.push(new ol.style.Style({
        text: new ol.style.Text({
          text: String(hnum),
          font: '9px Arial, sans-serif',
          fill: new ol.style.Fill({ color: '#888' }),
          stroke: new ol.style.Stroke({ color: 'rgba(255,255,255,0.9)', width: 2 }),
          overflow: true
        })
      }));
    }
  }
  return styles;
}

function vectorStyleFn(feature, resolution) {
  const z = Math.log2(156543.03392804103 / resolution);
  const layerName = feature.get('layer');
  const kind = feature.get('kind') || '';

  switch (layerName) {
    case 'earth':     return EARTH_STYLE;
    case 'landuse': {
      const color = LANDUSE_COLORS[kind];
      return color ? new ol.style.Style({ fill: new ol.style.Fill({ color }), zIndex: 2 }) : null;
    }
    case 'water': {
      const wname = getLabel(feature);
      if (!wname || z < 6) return WATER_STYLE;
      return [
        WATER_STYLE,
        new ol.style.Style({
          text: new ol.style.Text({
            text: wname,
            font: `italic ${z >= 10 ? 11 : 10}px Arial, sans-serif`,
            fill: new ol.style.Fill({ color: '#728dd4' }),
            stroke: new ol.style.Stroke({ color: 'rgba(255,255,255,0.85)', width: 2 }),
            overflow: true
          })
        })
      ];
    }
    case 'roads': {
      const kindDetail = feature.get('kind_detail') || '';
      const geomStyle = roadStyles(kind, z);
      const nameStyle = roadNameStyle(feature, z);
      const shieldStyle = roadShieldStyle(feature, z, kind, kindDetail);
      if (!geomStyle && !nameStyle && !shieldStyle) return null;
      const arr = Array.isArray(geomStyle) ? [...geomStyle] : (geomStyle ? [geomStyle] : []);
      if (nameStyle) arr.push(nameStyle);
      if (shieldStyle) arr.push(shieldStyle);
      return arr.length === 1 ? arr[0] : arr;
    }
    case 'buildings': return buildingStyle(feature, z);
    case 'places':    return placeStyle(feature, z);
    case 'pois': {
      if (z < 14) return null;
      const hnum = feature.get('housenumber');
      if (hnum && z >= 17) {
        return new ol.style.Style({
          text: new ol.style.Text({
            text: String(hnum),
            font: '9px Arial, sans-serif',
            fill: new ol.style.Fill({ color: '#888' }),
            stroke: new ol.style.Stroke({ color: 'rgba(255,255,255,0.9)', width: 2 }),
            overflow: true
          })
        });
      }
      const pname = getLabel(feature);
      if (!pname) return null;
      return new ol.style.Style({
        text: new ol.style.Text({
          text: pname,
          font: '10px Arial, sans-serif',
          fill: new ol.style.Fill({ color: '#5c5c5c' }),
          stroke: new ol.style.Stroke({ color: '#e0e0e0', width: 2.5 }),
          overflow: true
        })
      });
    }
    case 'addresses': {
      if (z < 17) return null;
      const hnum = feature.get('housenumber');
      if (!hnum) return null;
      return new ol.style.Style({
        text: new ol.style.Text({
          text: String(hnum),
          font: '9px Arial, sans-serif',
          fill: new ol.style.Fill({ color: '#888' }),
          stroke: new ol.style.Stroke({ color: 'rgba(255,255,255,0.9)', width: 2 }),
          overflow: true
        })
      });
    }
    case 'transit':
      return (kind === 'rail' || kind === 'subway') ? TRANSIT_RAIL : TRANSIT_OTHER;
    case 'boundaries':
      if (z < 3) return null;
      return kind === 'country' ? BOUNDARY_COUNTRY : BOUNDARY_REGION;
    default:          return null;
  }
}

// ── Draw interaction ──────────────────────────────────────────────────────────

function toggleDraw() {
  if (drawActive) {
    cancelDraw();
  } else {
    startDraw();
  }
}

function startDraw() {
  drawSource.clear();

  drawInteraction = new ol.interaction.Draw({
    source: drawSource,
    type: 'Circle',
    geometryFunction: ol.interaction.Draw.createBox()
  });

  drawInteraction.on('drawend', onDrawEnd);
  map.addInteraction(drawInteraction);
  drawActive = true;
  drawBtn.textContent = 'Cancel Draw';
  drawBtn.classList.add('active');
}

function cancelDraw() {
  if (drawInteraction) {
    map.removeInteraction(drawInteraction);
    drawInteraction = null;
  }
  drawSource.clear();
  drawActive = false;
  drawBtn.textContent = 'Draw Preload';
  drawBtn.classList.remove('active');
}

function onDrawEnd(event) {
  map.removeInteraction(drawInteraction);
  drawInteraction = null;
  drawActive = false;
  drawBtn.textContent = 'Draw Preload';
  drawBtn.classList.remove('active');

  const extent = event.feature.getGeometry().getExtent();
  const [west, south] = ol.proj.toLonLat([extent[0], extent[1]]);
  const [east, north] = ol.proj.toLonLat([extent[2], extent[3]]);

  pendingBbox = { north, south, east, west };
  showPreloadModal();
}

// ── Preload modal ─────────────────────────────────────────────────────────────

function showPreloadModal() {
  const { north, south, east, west } = pendingBbox;
  bboxDisplay.textContent =
    `N ${north.toFixed(4)}  S ${south.toFixed(4)}\nE ${east.toFixed(4)}  W ${west.toFixed(4)}`;
  zoomSlider.value = 15;
  zoomValue.textContent = '15';
  buildPreloadLayerCheckboxes();
  updatePreloadEstimates();
  preloadOverlay.classList.remove('hidden');
}

function hidePreloadModal() {
  preloadOverlay.classList.add('hidden');
  drawSource.clear();
  pendingBbox = null;
  preloadNameInput.value = '';
  preloadAllowedUsers.value = '';
  preloadAllowedGroups.value = '';
}

function buildPreloadLayerCheckboxes() {
  preloadLayersContainer.innerHTML = '';
  appendPreloadLayerRow(VECTOR_LAYER_KEY, 'OSM Vector', { recommended: true, checked: true });
  Object.keys(layerMap).forEach((id) => {
    const layer = layerMap[id];
    if (layer.sourceType === 'LOCAL') return;
    if (layerHasTimeComponent(layer)) return;
    appendPreloadLayerRow(id, layer.name || id, { recommended: false, checked: false });
  });
}

function layerHasTimeComponent(layer) {
  if (!layer) return false;
  if (layer.wmtsTime === true) return true;
  return typeof layer.urlTemplate === 'string' && layer.urlTemplate.includes('{time}');
}

function appendPreloadLayerRow(key, label, { recommended, checked }) {
  const row = document.createElement('div');
  row.className = 'preload-layer-row';
  row.dataset.key = key;
  const id = `preload-layer-${cssEscape(key)}`;
  row.innerHTML =
    `<input type="checkbox" id="${id}" ${checked ? 'checked' : ''}>` +
    `<label for="${id}">${escapeHtml(label)}</label>` +
    (recommended ? '<span class="recommended-tag">Recommended</span>' : '') +
    '<span class="tile-estimate"></span>';
  row.querySelector('input').addEventListener('change', updatePreloadEstimates);
  preloadLayersContainer.appendChild(row);
}

function getSelectedPreloadLayerKeys() {
  return Array.from(preloadLayersContainer.querySelectorAll('input[type="checkbox"]:checked'))
    .map((el) => el.parentElement.dataset.key);
}

function updatePreloadEstimates() {
  if (!pendingBbox) return;
  const maxZoom = parseInt(zoomSlider.value, 10);
  let xyzSelected = false;

  preloadLayersContainer.querySelectorAll('.preload-layer-row').forEach((row) => {
    const key = row.dataset.key;
    const checked = row.querySelector('input').checked;
    const estimateEl = row.querySelector('.tile-estimate');

    if (key === VECTOR_LAYER_KEY) {
      estimateEl.textContent = '';
      return;
    }

    if (!checked) {
      estimateEl.textContent = '';
      estimateEl.classList.remove('high');
      return;
    }

    xyzSelected = true;
    const layer = layerMap[key];
    const cap = layer && Number.isFinite(layer.maxZoom) ? Math.min(maxZoom, layer.maxZoom) : maxZoom;
    const count = countTilesInBbox(pendingBbox, cap);
    estimateEl.textContent = `~${formatTileCount(count)} tiles`;
    estimateEl.classList.toggle('high', count >= 100000);
  });

  preloadWarningRow.classList.toggle('hidden', !xyzSelected);
}

function countTilesInBbox(bbox, maxZoom) {
  let total = 0;
  for (let z = 0; z <= maxZoom; z++) {
    const n = Math.pow(2, z);
    const xMin = clampTile(Math.floor((bbox.west + 180) / 360 * n), n);
    const xMax = clampTile(Math.floor((bbox.east + 180) / 360 * n), n);
    const yMin = clampTile(latToTileY(bbox.north, n), n);
    const yMax = clampTile(latToTileY(bbox.south, n), n);
    total += (xMax - xMin + 1) * (yMax - yMin + 1);
  }
  return total;
}

function latToTileY(lat, n) {
  const rad = lat * Math.PI / 180;
  return Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n);
}

function clampTile(v, n) {
  if (v < 0) return 0;
  if (v >= n) return n - 1;
  return v;
}

function formatTileCount(n) {
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(n < 10_000 ? 1 : 0)}K`;
  return `${(n / 1_000_000).toFixed(n < 10_000_000 ? 1 : 0)}M`;
}

function cssEscape(s) {
  return String(s).replace(/[^a-zA-Z0-9_-]/g, '_');
}

async function submitPreload() {
  if (!pendingBbox) return;
  if (!isAdmin()) {
    showToast('Admin role required', 'error');
    return;
  }

  const selected = getSelectedPreloadLayerKeys();
  if (selected.length === 0) {
    showToast('Select at least one layer', 'warning');
    return;
  }

  const maxZoom = parseInt(zoomSlider.value, 10);
  const bbox = { ...pendingBbox };
  const name = preloadNameInput.value.trim() || null;

  const xyzLayers = selected.filter((k) => k !== VECTOR_LAYER_KEY);
  const includeVector = selected.includes(VECTOR_LAYER_KEY);
  const allowedUsers = splitList(preloadAllowedUsers.value);
  const allowedGroups = splitList(preloadAllowedGroups.value);

  hidePreloadModal();

  try {
    const resp = await authFetch('/preloads', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name,
        boundingBox: bbox,
        maxZoom: includeVector ? Math.min(maxZoom, VECTOR_MAX_ZOOM) : maxZoom,
        layers: xyzLayers,
        includeVector,
        allowedUsers,
        allowedGroups
      })
    });

    if (resp.status === 202) {
      showToast('Preload started', 'success');
      openDownloadsPanel();
      return;
    }
    if (resp.status === 409) showToast('A preload is already in progress', 'warning');
    else if (resp.status === 401 || resp.status === 403) showToast('Login required (admin role)', 'error');
    else if (resp.status === 503) showToast('Download directory not configured on server', 'error');
    else showToast(`Preload failed (${resp.status})`, 'error');
  } catch (e) {
    showToast('Network error submitting preload', 'error');
  }
}

// ── Downloads panel ───────────────────────────────────────────────────────────

function toggleDownloadsPanel() {
  if (downloadsPanel.classList.contains('open')) {
    closeDownloadsPanel();
  } else {
    openDownloadsPanel();
  }
}

function openDownloadsPanel() {
  downloadsPanel.classList.add('open');
  downloadsBtn.classList.add('active');
  loadDownloads();
  startDownloadPolling();
}

function closeDownloadsPanel() {
  downloadsPanel.classList.remove('open');
  downloadsBtn.classList.remove('active');
  if (highlightSource) highlightSource.clear();
  stopDownloadPolling();
}

function startDownloadPolling() {
  stopDownloadPolling();
  downloadsPollInterval = setInterval(loadDownloads, 5000);
}

function stopDownloadPolling() {
  if (downloadsPollInterval !== null) {
    clearInterval(downloadsPollInterval);
    downloadsPollInterval = null;
  }
}

async function loadDownloads() {
  try {
    const resp = await authFetch('/preloads');
    if (!resp.ok) {
      downloadsList.innerHTML = '<li class="empty-state">Preloads unavailable</li>';
      return;
    }
    renderDownloads(await resp.json());
  } catch (e) {
    downloadsList.innerHTML = '<li class="empty-state">Failed to load preloads</li>';
  }
}

function renderDownloads(preloads) {
  if (!preloads || preloads.length === 0) {
    currentDownloads = [];
    downloadsList.innerHTML = '<li class="empty-state">No preloads yet</li>';
    return;
  }

  currentDownloads = preloads.map((p) => ({
    ...p,
    bounds: bboxToBounds(p.boundingBox)
  }));

  downloadsList.innerHTML = currentDownloads.map((p, i) => {
    const date = p.createdAt ? new Date(p.createdAt).toLocaleString() : 'Unknown date';
    const sizeLine = p.sizeBytes != null ? `${formatBytes(p.sizeBytes)} &bull; ` : '';
    const hasBbox = Array.isArray(p.bounds) && p.bounds.length === 4;
    const boundsLine = hasBbox
      ? `W ${p.bounds[0].toFixed(2)} S ${p.bounds[1].toFixed(2)} ` +
        `E ${p.bounds[2].toFixed(2)} N ${p.bounds[3].toFixed(2)}<br>`
      : '';
    const hint = hasBbox ? ' title="Click to show on map"' : '';
    const layerLabels = buildLayerLabels(p);
    const layersLine = layerLabels.length
      ? `<div class="download-layers">${layerLabels.map((l) => `<span class="layer-chip">${escapeHtml(l)}</span>`).join('')}</div>`
      : '';
    const isPublic =
      (!p.allowedUsers || p.allowedUsers.length === 0) &&
      (!p.allowedGroups || p.allowedGroups.length === 0);
    const accessLine = isAdmin() && !isPublic
      ? `<div class="download-access">Restricted</div>`
      : '';

    return `<li class="download-item" data-index="${i}"${hint}>
      <div class="download-name">${escapeHtml(p.name || '(unnamed)')}${accessLine}</div>
      ${layersLine}
      <div class="download-meta">
        ${sizeLine}Zoom &le; ${p.maxZoom}<br>
        ${boundsLine}${date}
      </div>
    </li>`;
  }).join('');
}

function buildLayerLabels(p) {
  const labels = [];
  if (p.includesVector) labels.push('OSM Vector');
  if (Array.isArray(p.layers)) labels.push(...p.layers);
  return labels;
}

function bboxToBounds(bbox) {
  if (!bbox) return null;
  return [bbox.west, bbox.south, bbox.east, bbox.north];
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function splitList(s) {
  return s.split(',').map((x) => x.trim()).filter(Boolean);
}

function formatBytes(bytes) {
  if (bytes == null) return 'Unknown size';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// ── GeoTIFF upload ────────────────────────────────────────────────────────────

function showGeoTiffModal() {
  geoTiffNameInput.value = '';
  geoTiffFileInput.value = '';
  geoTiffStatus.textContent = '';
  geoTiffAllowedUsers.value = '';
  geoTiffAllowedGroups.value = '';
  geoTiffOverlay.classList.remove('hidden');
}

function hideGeoTiffModal() {
  geoTiffOverlay.classList.add('hidden');
}

async function submitGeoTiff() {
  if (!isAdmin()) {
    geoTiffStatus.textContent = 'Admin role required.';
    return;
  }
  const name = geoTiffNameInput.value.trim();
  const file = geoTiffFileInput.files[0];

  if (!name) {
    geoTiffStatus.textContent = 'Layer name is required.';
    return;
  }
  if (!file) {
    geoTiffStatus.textContent = 'Choose a GeoTIFF file.';
    return;
  }

  const fd = new FormData();
  fd.append('name', name);
  fd.append('file', file);
  const users = geoTiffAllowedUsers.value.trim();
  const groups = geoTiffAllowedGroups.value.trim();
  if (users) fd.append('allowedUsers', users);
  if (groups) fd.append('allowedGroups', groups);

  geoTiffStatus.textContent = 'Uploading and tiling — this may take a while…';
  const submitBtn = document.getElementById('submit-geotiff');
  submitBtn.disabled = true;

  try {
    const resp = await authFetch('/layers/geotiff', {
      method: 'POST',
      body: fd
    });
    if (resp.status === 201) {
      const layer = await resp.json();
      const layerKey = layer.id || layer.name;
      hideGeoTiffModal();
      showToast(`Layer '${layer.name || layerKey}' created (max zoom ${layer.maxZoom})`, 'success');
      addLayerToSelect(layer);
      switchLayer(layerKey);
      layerSelect.value = layerKey;
      return;
    }
    const text = await resp.text();
    if (resp.status === 401 || resp.status === 403) {
      geoTiffStatus.textContent = 'Login required (admin role).';
    } else if (resp.status === 409) {
      geoTiffStatus.textContent = text || 'Layer already exists.';
    } else if (resp.status === 422) {
      geoTiffStatus.textContent = text || 'Tiling failed.';
    } else {
      geoTiffStatus.textContent = `Upload failed (${resp.status}): ${text}`;
    }
  } catch (e) {
    geoTiffStatus.textContent = 'Network error during upload.';
  } finally {
    submitBtn.disabled = false;
  }
}

function addLayerToSelect(layer) {
  const key = layer.id || layer.name;
  layerMap[key] = layer;
  const opt = document.createElement('option');
  opt.value = key;
  opt.textContent = layer.name || key;
  const vectorOpt = layerSelect.querySelector('option[value="__vector__"]');
  if (vectorOpt) {
    layerSelect.insertBefore(opt, vectorOpt);
  } else {
    layerSelect.appendChild(opt);
  }
}

// ── Layer manager ─────────────────────────────────────────────────────────────

function showLayerManager() {
  lmDeleteConfirm = null;
  renderLayerManagerList();
  layerManagerOverlay.classList.remove('hidden');
}

function hideLayerManager() {
  layerManagerOverlay.classList.add('hidden');
  editingLayerName = null;
  lmDeleteConfirm = null;
}

function showLayerManagerView(view) {
  lmListView.classList.toggle('hidden', view !== 'list');
  lmFormView.classList.toggle('hidden', view !== 'form');
  layerManagerTitle.textContent =
    view === 'list' ? 'Manage Layers' : (editingLayerName ? 'Edit Layer' : 'Add Layer');
}

function renderLayerManagerList() {
  showLayerManagerView('list');
  const layers = Object.values(layerMap);
  if (layers.length === 0) {
    lmLayerList.innerHTML = '<div class="empty-state">No layers configured</div>';
    return;
  }
  lmLayerList.innerHTML = layers.map((layer) => {
    const layerId = layer.id || layer.name;
    const displayName = layer.name || layerId;
    const isPublic =
      (!layer.allowedUsers || layer.allowedUsers.length === 0) &&
      (!layer.allowedGroups || layer.allowedGroups.length === 0);
    const access = isPublic ? 'Public' : 'Restricted';
    if (lmDeleteConfirm === layerId) {
      return `<div class="lm-layer-row" data-name="${escapeHtml(layerId)}">
        <span class="lm-confirm-text">Delete &ldquo;${escapeHtml(displayName)}&rdquo;?</span>
        <div class="lm-row-actions">
          <button class="btn-danger lm-confirm-del">Delete</button>
          <button class="lm-cancel-del">Cancel</button>
        </div>
      </div>`;
    }
    return `<div class="lm-layer-row" data-name="${escapeHtml(layerId)}">
      <div class="lm-row-info">
        <span class="lm-row-name">${escapeHtml(displayName)}</span>
        <span class="lm-row-meta">${escapeHtml(layerId)} &bull; ${escapeHtml(layer.sourceType || 'XYZ')} &bull; ${access}</span>
      </div>
      <div class="lm-row-actions">
        <button class="lm-edit-btn">Edit</button>
        <button class="lm-delete-btn">Delete</button>
      </div>
    </div>`;
  }).join('');

  lmLayerList.querySelectorAll('.lm-edit-btn').forEach((btn) => {
    btn.addEventListener('click', () => showEditLayerForm(btn.closest('.lm-layer-row').dataset.name));
  });
  lmLayerList.querySelectorAll('.lm-delete-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      lmDeleteConfirm = btn.closest('.lm-layer-row').dataset.name;
      renderLayerManagerList();
    });
  });
  lmLayerList.querySelectorAll('.lm-confirm-del').forEach((btn) => {
    btn.addEventListener('click', () => executeDeleteLayer(btn.closest('.lm-layer-row').dataset.name));
  });
  lmLayerList.querySelectorAll('.lm-cancel-del').forEach((btn) => {
    btn.addEventListener('click', () => {
      lmDeleteConfirm = null;
      renderLayerManagerList();
    });
  });
}

function showAddLayerForm() {
  editingLayerName = null;
  populateLayerForm(null);
  showLayerManagerView('form');
}

function showEditLayerForm(name) {
  editingLayerName = name;
  populateLayerForm(layerMap[name]);
  showLayerManagerView('form');
}

function populateLayerForm(layer) {
  const layerId = layer ? (layer.id || layer.name) : '';
  lfId.value = layerId;
  lfId.readOnly = !!layer;
  lfName.value = layer ? (layer.name || layerId) : '';
  lfSourceType.value = layer ? (layer.sourceType || 'XYZ') : 'XYZ';
  lfUrl.value = layer ? (layer.urlTemplate || '') : '';
  lfAttribution.value = layer ? (layer.attribution || '') : '';
  lfMaxZoom.value = layer != null && layer.maxZoom != null ? layer.maxZoom : 22;
  lfExpiration.value = layer ? (layer.tileExpirationMinutes || 0) : 0;
  lfAllowedUsers.value = layer ? (layer.allowedUsers || []).join(', ') : '';
  lfAllowedGroups.value = layer ? (layer.allowedGroups || []).join(', ') : '';
  lfWmtsLayer.value = layer ? (layer.wmtsLayerName || '') : '';
  lfWmtsMatrix.value = layer ? (layer.wmtsTileMatrixSet || 'EPSG:3857') : 'EPSG:3857';
  lfWmtsStyle.value = layer ? (layer.wmtsStyle || 'default') : 'default';
  lfWmtsFormat.value = layer ? (layer.wmtsFormat || 'image/png') : 'image/png';
  lfWmtsTime.checked = layer ? (layer.wmtsTime || false) : false;
  onSourceTypeChange();
}

function onSourceTypeChange() {
  const type = lfSourceType.value;
  lfWmtsSection.classList.toggle('hidden', type !== 'WMTS_KVP');
  lfUrlRow.classList.toggle('hidden', type === 'LOCAL');
}

function readLayerForm() {
  const id = lfId.value.trim();
  if (!id) {
    showToast('Layer ID is required', 'error');
    return null;
  }
  const name = lfName.value.trim() || id;
  const sourceType = lfSourceType.value;
  const urlTemplate = lfUrl.value.trim();
  if (sourceType !== 'LOCAL' && !urlTemplate) {
    showToast('URL Template is required', 'error');
    return null;
  }
  const layer = {
    id,
    name,
    sourceType,
    urlTemplate,
    attribution: lfAttribution.value.trim() || null,
    maxZoom: parseInt(lfMaxZoom.value, 10) || 22,
    tileExpirationMinutes: parseInt(lfExpiration.value, 10) || 0,
    allowedUsers: splitList(lfAllowedUsers.value),
    allowedGroups: splitList(lfAllowedGroups.value)
  };
  if (sourceType === 'WMTS_KVP') {
    layer.wmtsLayerName = lfWmtsLayer.value.trim();
    layer.wmtsTileMatrixSet = lfWmtsMatrix.value.trim() || 'EPSG:3857';
    layer.wmtsStyle = lfWmtsStyle.value.trim() || 'default';
    layer.wmtsFormat = lfWmtsFormat.value.trim() || 'image/png';
    layer.wmtsTime = lfWmtsTime.checked;
  }
  return layer;
}

async function saveLayer() {
  const layer = readLayerForm();
  if (!layer) return;
  const saveBtn = document.getElementById('lm-save-btn');
  saveBtn.disabled = true;
  try {
    let resp;
    if (editingLayerName) {
      resp = await authFetch(`/layers/${encodeURIComponent(editingLayerName)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(layer)
      });
    } else {
      resp = await authFetch('/layers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(layer)
      });
    }
    if (resp.status === 200 || resp.status === 201) {
      const display = layer.name || layer.id;
      showToast(editingLayerName ? `Layer '${display}' updated` : `Layer '${display}' added`, 'success');
      await loadLayers();
      lmDeleteConfirm = null;
      renderLayerManagerList();
      return;
    }
    const text = await resp.text();
    if (resp.status === 401 || resp.status === 403) showToast('Admin role required', 'error');
    else if (resp.status === 409) showToast(text || 'Layer already exists', 'error');
    else if (resp.status === 400) showToast(text || 'Invalid layer data', 'error');
    else showToast(`Save failed (${resp.status})`, 'error');
  } catch (e) {
    showToast('Network error saving layer', 'error');
  } finally {
    saveBtn.disabled = false;
  }
}

async function executeDeleteLayer(name) {
  try {
    const resp = await authFetch(`/layers/${encodeURIComponent(name)}`, { method: 'DELETE' });
    if (resp.status === 204) {
      showToast(`Layer '${name}' deleted`, 'success');
      await loadLayers();
      lmDeleteConfirm = null;
      renderLayerManagerList();
      return;
    }
    if (resp.status === 401 || resp.status === 403) showToast('Admin role required', 'error');
    else if (resp.status === 404) showToast('Layer not found', 'error');
    else showToast(`Delete failed (${resp.status})`, 'error');
  } catch (e) {
    showToast('Network error deleting layer', 'error');
  }
}

// ── Export modal ──────────────────────────────────────────────────────────────

function showExportModal() {
  if (!isLoggedIn()) { showToast('Login required', 'error'); return; }
  exportStatus.textContent = '';
  exportMaxZoom.value = '';
  buildExportLayerCheckboxes();
  renderExportBbox();
  exportOverlay.classList.remove('hidden');
}

function hideExportModal() {
  exportOverlay.classList.add('hidden');
  cancelExportDrawInteraction();
}

function buildExportLayerCheckboxes() {
  exportLayersContainer.innerHTML = '';
  const ids = Object.keys(layerMap).filter((id) => !isVectorLayerEntry(layerMap[id]));
  if (ids.length === 0) {
    exportLayersContainer.innerHTML = '<div class="empty-state">No raster layers available</div>';
    return;
  }
  ids.forEach((id) => {
    const layer = layerMap[id];
    const row = document.createElement('div');
    row.className = 'preload-layer-row';
    row.dataset.key = id;
    const cbid = `export-layer-${cssEscape(id)}`;
    row.innerHTML =
      `<input type="checkbox" id="${cbid}">` +
      `<label for="${cbid}">${escapeHtml(layer.name || id)}</label>`;
    exportLayersContainer.appendChild(row);
  });
}

function isVectorLayerEntry(layer) {
  const url = (layer && layer.urlTemplate) || '';
  const lower = url.toLowerCase();
  return lower.startsWith('pmtiles://') || lower.endsWith('.pbf') || lower.endsWith('.mvt');
}

function getSelectedExportLayerKeys() {
  return Array.from(exportLayersContainer.querySelectorAll('input[type="checkbox"]:checked'))
    .map((el) => el.parentElement.dataset.key);
}

function renderExportBbox() {
  if (pendingExportBbox) {
    const { north, south, east, west } = pendingExportBbox;
    exportBboxDisplay.textContent =
      `N ${north.toFixed(4)}  S ${south.toFixed(4)}\nE ${east.toFixed(4)}  W ${west.toFixed(4)}`;
    exportClearBboxBtn.disabled = false;
  } else {
    exportBboxDisplay.textContent =
      'No bounding box — exporting all cached tiles for the selected layers.';
    exportClearBboxBtn.disabled = true;
  }
}

function clearExportBbox() {
  pendingExportBbox = null;
  drawSource.clear();
  renderExportBbox();
}

function startExportDraw() {
  cancelExportDrawInteraction();
  drawSource.clear();
  exportOverlay.classList.add('hidden');
  exportDrawInteraction = new ol.interaction.Draw({
    source: drawSource,
    type: 'Circle',
    geometryFunction: ol.interaction.Draw.createBox()
  });
  exportDrawInteraction.on('drawend', onExportDrawEnd);
  map.addInteraction(exportDrawInteraction);
}

function cancelExportDrawInteraction() {
  if (exportDrawInteraction) {
    map.removeInteraction(exportDrawInteraction);
    exportDrawInteraction = null;
  }
}

function onExportDrawEnd(event) {
  cancelExportDrawInteraction();
  const extent = event.feature.getGeometry().getExtent();
  const [west, south] = ol.proj.toLonLat([extent[0], extent[1]]);
  const [east, north] = ol.proj.toLonLat([extent[2], extent[3]]);
  pendingExportBbox = { north, south, east, west };
  renderExportBbox();
  exportOverlay.classList.remove('hidden');
}

async function submitExport() {
  if (!isLoggedIn()) {
    exportStatus.textContent = 'Login required.';
    return;
  }
  const layers = getSelectedExportLayerKeys();
  if (layers.length === 0) {
    exportStatus.textContent = 'Select at least one layer.';
    return;
  }
  const body = { layers };
  if (pendingExportBbox) {
    body.boundingBox = { ...pendingExportBbox, maxZoom: 22 };
  }
  const maxZoomRaw = exportMaxZoom.value.trim();
  if (maxZoomRaw) {
    const mz = parseInt(maxZoomRaw, 10);
    if (Number.isFinite(mz) && mz >= 0) body.maxZoom = mz;
  }

  const submitBtn = document.getElementById('submit-export');
  submitBtn.disabled = true;
  exportStatus.textContent = 'Building zip — this may take a while…';
  try {
    const resp = await authFetch('/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!resp.ok) {
      const text = await resp.text();
      if (resp.status === 401 || resp.status === 403) {
        exportStatus.textContent = 'Access denied: ' + (text || resp.status);
      } else if (resp.status === 404) {
        exportStatus.textContent = text || 'Layer not found.';
      } else {
        exportStatus.textContent = `Export failed (${resp.status}): ${text}`;
      }
      return;
    }
    const disp = resp.headers.get('Content-Disposition') || '';
    const fnMatch = /filename="?([^";]+)"?/.exec(disp);
    const filename = fnMatch ? fnMatch[1] : `tile-export-${Date.now()}.zip`;
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    hideExportModal();
    showToast('Export downloaded', 'success');
  } catch (e) {
    exportStatus.textContent = 'Network error during export.';
  } finally {
    submitBtn.disabled = false;
  }
}

// ── Import modal ──────────────────────────────────────────────────────────────

function showImportModal() {
  if (!isLoggedIn()) { showToast('Login required', 'error'); return; }
  importFileInput.value = '';
  importStatus.textContent = '';
  importOverlay.classList.remove('hidden');
}

function hideImportModal() {
  importOverlay.classList.add('hidden');
}

async function submitImport() {
  if (!isLoggedIn()) {
    importStatus.textContent = 'Login required.';
    return;
  }
  const file = importFileInput.files[0];
  if (!file) {
    importStatus.textContent = 'Choose a zip file.';
    return;
  }
  const fd = new FormData();
  fd.append('file', file);
  const submitBtn = document.getElementById('submit-import');
  submitBtn.disabled = true;
  importStatus.textContent = 'Uploading and ingesting — this may take a while…';
  try {
    const resp = await authFetch('/import', { method: 'POST', body: fd });
    if (!resp.ok) {
      const text = await resp.text();
      if (resp.status === 401 || resp.status === 403) {
        importStatus.textContent = 'Login required.';
      } else {
        importStatus.textContent = `Import failed (${resp.status}): ${text}`;
      }
      return;
    }
    const summary = await resp.json();
    hideImportModal();
    const added = (summary.layersAdded || []).length;
    const skipped = (summary.layersSkipped || []).length;
    showToast(
      `Imported ${summary.tilesWritten} tiles · ${added} layer(s) added, ${skipped} skipped`,
      'success'
    );
    await loadLayers();
  } catch (e) {
    importStatus.textContent = 'Network error during import.';
  } finally {
    submitBtn.disabled = false;
  }
}

function showToast(message, type) {
  const toast = document.createElement('div');
  toast.className = `toast ${type || 'success'}`;
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => {
    toast.classList.add('fade-out');
    setTimeout(() => toast.remove(), 300);
  }, 2700);
}
