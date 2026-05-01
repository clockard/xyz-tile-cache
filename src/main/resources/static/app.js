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

// ── DOM refs ──────────────────────────────────────────────────────────────────

let layerSelect, drawBtn, downloadsBtn, uploadGeoTiffBtn;
let downloadsPanel, downloadsList;
let preloadOverlay, bboxDisplay, zoomSlider, zoomValue, adminKeyInput, preloadNameInput;
let preloadLayersContainer, preloadWarningRow;
let zoomIndicator, attributionEl;
let geoTiffOverlay, geoTiffNameInput, geoTiffFileInput, geoTiffAdminKeyInput, geoTiffStatus;

const VECTOR_LAYER_KEY = '__vector__';
const VECTOR_MAX_ZOOM = 15;

// ── Init ──────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  layerSelect   = document.getElementById('layer-select');
  drawBtn       = document.getElementById('draw-btn');
  downloadsBtn  = document.getElementById('downloads-btn');
  uploadGeoTiffBtn = document.getElementById('upload-geotiff-btn');
  downloadsPanel = document.getElementById('downloads-panel');
  downloadsList  = document.getElementById('downloads-list');
  preloadOverlay = document.getElementById('preload-overlay');
  bboxDisplay    = document.getElementById('bbox-display');
  zoomSlider     = document.getElementById('zoom-slider');
  zoomValue      = document.getElementById('zoom-value');
  adminKeyInput  = document.getElementById('admin-key-input');
  preloadNameInput = document.getElementById('preload-name-input');
  preloadLayersContainer = document.getElementById('preload-layers');
  preloadWarningRow = document.getElementById('preload-warning-row');
  zoomIndicator  = document.getElementById('zoom-indicator');
  attributionEl  = document.getElementById('attribution');
  geoTiffOverlay = document.getElementById('geotiff-overlay');
  geoTiffNameInput = document.getElementById('geotiff-name-input');
  geoTiffFileInput = document.getElementById('geotiff-file-input');
  geoTiffAdminKeyInput = document.getElementById('geotiff-admin-key-input');
  geoTiffStatus = document.getElementById('geotiff-status');

  adminKeyInput.value = localStorage.getItem('xyz-admin-key') || '';
  geoTiffAdminKeyInput.value = localStorage.getItem('xyz-admin-key') || '';

  zoomSlider.addEventListener('input', () => {
    zoomValue.textContent = zoomSlider.value;
    updatePreloadEstimates();
  });

  layerSelect.addEventListener('change', () => switchLayer(layerSelect.value));
  drawBtn.addEventListener('click', toggleDraw);
  downloadsBtn.addEventListener('click', toggleDownloadsPanel);
  uploadGeoTiffBtn.addEventListener('click', showGeoTiffModal);
  document.getElementById('close-downloads').addEventListener('click', closeDownloadsPanel);
  document.getElementById('submit-preload').addEventListener('click', submitPreload);
  document.getElementById('cancel-preload').addEventListener('click', hidePreloadModal);
  document.getElementById('submit-geotiff').addEventListener('click', submitGeoTiff);
  document.getElementById('cancel-geotiff').addEventListener('click', hideGeoTiffModal);

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
  loadLayers();
});

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

// ── Layer loading ─────────────────────────────────────────────────────────────

async function loadLayers() {
  try {
    const resp = await fetch('/layers');
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const layers = await resp.json();

    layers.forEach((layer) => {
      layerMap[layer.name] = layer;
      const opt = document.createElement('option');
      opt.value = layer.name;
      opt.textContent = layer.name;
      layerSelect.appendChild(opt);
    });

    const vectorOpt = document.createElement('option');
    vectorOpt.value = '__vector__';
    vectorOpt.textContent = 'OSM Vector';
    layerSelect.appendChild(vectorOpt);

    if (layers.length > 0) {
      switchLayer(layers[0].name);
    }
  } catch (e) {
    showToast('Failed to load layers', 'error');
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
        crossOrigin: 'anonymous'
      })
    })
  );
  updateAttribution(layer.attribution || '');
}

function switchToVector() {
  setMapTileLayer(
    new ol.layer.VectorTile({
      declutter: true,
      source: new ol.source.VectorTile({
        url: '/vector/{z}/{x}/{y}',
        format: new ol.format.MVT()
      }),
      style: vectorStyleFn
    })
  );
  updateAttribution('© <a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org">OpenStreetMap</a>');
}

// ── Vector tile style ─────────────────────────────────────────────────────────

const EARTH_STYLE = new ol.style.Style({ fill: new ol.style.Fill({ color: '#f2efe9' }) });

const WATER_STYLE = new ol.style.Style({
  fill: new ol.style.Fill({ color: '#aad3df' }),
  stroke: new ol.style.Stroke({ color: '#73b5d4', width: 0.5 })
});

const TRANSIT_RAIL = [
  new ol.style.Style({ stroke: new ol.style.Stroke({ color: '#707070', width: 3 }) }),
  new ol.style.Style({ stroke: new ol.style.Stroke({ color: '#ffffff', width: 1.5, lineDash: [8, 8] }) })
];
const TRANSIT_OTHER = new ol.style.Style({
  stroke: new ol.style.Stroke({ color: '#aaaaaa', width: 1, lineDash: [6, 4] })
});

const BOUNDARY_COUNTRY = new ol.style.Style({
  stroke: new ol.style.Stroke({ color: '#ac46ac', width: 1.5, lineDash: [8, 4] })
});
const BOUNDARY_REGION = new ol.style.Style({
  stroke: new ol.style.Stroke({ color: '#9e9ebb', width: 1, lineDash: [8, 6] })
});

const LANDUSE_COLORS = {
  park: '#cdebb0',            grass: '#cdebb0',            meadow: '#cdebb0',
  village_green: '#cdebb0',   recreation_ground: '#cdebb0', garden: '#cdebb0',
  forest: '#add19e',          wood: '#add19e',
  scrub: '#c8d7ab',
  golf_course: '#def6c0',     cemetery: '#aacbaf',
  farmland: '#eef0d5',        farm: '#eef0d5',             allotments: '#c9e1bf',
  farmyard: '#f5dcba',
  industrial: '#e8e8e8',      commercial: '#f2dad9',       retail: '#ffd6d1',
  residential: '#e0dfdf',
  school: '#f0f0d8',          college: '#f0f0d8',          university: '#f0f0d8',
  hospital: '#e8d9d9',        clinic: '#e8d9d9',
  beach: '#f5e9c6',           sand: '#f5e9c6',
  wetland: '#d4e8b0',         marsh: '#d4e8b0',
  parking: '#eeeeee',         aerodrome: '#dadae0',        military: '#d7c8ad'
};

const ROAD_SPECS = {
  highway:      { fill: '#e892a2', case_: '#dc2a67', base: 6 },
  motorway:     { fill: '#e892a2', case_: '#dc2a67', base: 6 },
  motorway_link:{ fill: '#e892a2', case_: '#dc2a67', base: 4 },
  trunk:        { fill: '#f9b29c', case_: '#c84e2f', base: 5 },
  trunk_link:   { fill: '#f9b29c', case_: '#c84e2f', base: 3.5 },
  primary:      { fill: '#fcd6a4', case_: '#a06b00', base: 4.5 },
  primary_link: { fill: '#fcd6a4', case_: '#a06b00', base: 3 },
  major_road:   { fill: '#fcd6a4', case_: '#a06b00', base: 4.5 },
  secondary:    { fill: '#f7fabf', case_: '#707d05', base: 3.5 },
  secondary_link:{ fill: '#f7fabf', case_: '#707d05', base: 2.5 },
  tertiary:     { fill: '#ffffff', case_: '#8f8f8f', base: 3 },
  tertiary_link: { fill: '#ffffff', case_: '#8f8f8f', base: 2 },
  minor_road:   { fill: '#ffffff', case_: '#b8b8b8', base: 2 },
  residential:  { fill: '#ffffff', case_: '#b8b8b8', base: 2 },
  service:      { fill: '#ffffff', case_: '#c0c0c0', base: 1 },
  track:        { fill: '#996600', case_: null, base: 1.5, dash: [6, 3] },
  path:         { fill: '#fa8072', case_: null, base: 1,   dash: [4, 3] },
  footway:      { fill: '#fa8072', case_: null, base: 1,   dash: [4, 2] },
  cycleway:     { fill: '#0000ff', case_: null, base: 1,   dash: [4, 4] },
  ferry:        { fill: '#66a3d2', case_: null, base: 1.5, dash: [12, 6] }
};

const MAIN_ROAD_KINDS = new Set(['highway', 'motorway', 'trunk', 'major_road', 'primary']);
const HIGH_ZOOM_KINDS = new Set(['path', 'footway', 'cycleway', 'service', 'residential']);

function roadStyles(kind, z) {
  if (z < 5 && !MAIN_ROAD_KINDS.has(kind)) return null;
  if (z < 7 && !MAIN_ROAD_KINDS.has(kind) && kind !== 'secondary') return null;
  if (z < 10 && HIGH_ZOOM_KINDS.has(kind)) return null;

  const spec = ROAD_SPECS[kind] || ROAD_SPECS['minor_road'];
  const scale = z >= 14 ? 2.0 : z >= 12 ? 1.5 : z >= 10 ? 1.0 : z >= 8 ? 0.7 : 0.5;
  const fillWidth = spec.base * scale;

  if (spec.dash) {
    return new ol.style.Style({
      stroke: new ol.style.Stroke({ color: spec.fill, width: fillWidth, lineDash: spec.dash })
    });
  }

  const caseWidth = fillWidth + (z >= 12 ? 2.5 : 1.5);
  return [
    new ol.style.Style({ stroke: new ol.style.Stroke({ color: spec.case_, width: caseWidth }) }),
    new ol.style.Style({ stroke: new ol.style.Stroke({ color: spec.fill, width: fillWidth }) })
  ];
}

function getLabel(feature) {
  return feature.get('name:en') || feature.get('name');
}

function roadNameStyle(feature, z) {
  if (z < 12) return null;
  const name = getLabel(feature);
  if (!name) return null;
  const kind = feature.get('kind') || '';
  if (z < 14 && HIGH_ZOOM_KINDS.has(kind)) return null;
  const fontSize = z >= 14 ? 11 : 10;
  return new ol.style.Style({
    text: new ol.style.Text({
      text: name,
      font: `${fontSize}px Arial, sans-serif`,
      fill: new ol.style.Fill({ color: '#333' }),
      stroke: new ol.style.Stroke({ color: 'rgba(255,255,255,0.9)', width: 3 }),
      placement: 'line',
      overflow: false
    })
  });
}

const PLACE_SPECS = {
  continent:    { minZ: 0,  maxSize: 16, minSize: 13, weight: '700', color: '#333333', upper: true },
  country:      { minZ: 1,  maxSize: 18, minSize: 13, weight: '700', color: '#333333', upper: true },
  state:        { minZ: 4,  maxSize: 15, minSize: 13, weight: '600', color: '#555555', upper: true },
  province:     { minZ: 4,  maxSize: 15, minSize: 13, weight: '600', color: '#555555', upper: true },
  city:         { minZ: 3,  maxSize: 18, minSize: 14, weight: '700', color: '#111111' },
  town:         { minZ: 8,  maxSize: 14, minSize: 12, weight: '500', color: '#333333' },
  village:      { minZ: 11, maxSize: 12, minSize: 11, weight: '400', color: '#444444' },
  suburb:       { minZ: 12, maxSize: 12, minSize: 11, weight: '400', color: '#555555' },
  neighborhood: { minZ: 13, maxSize: 11, minSize: 10, weight: '400', color: '#666666' },
  locality:     { minZ: 13, maxSize: 11, minSize: 10, weight: '400', color: '#555555' }
};

function placeStyle(feature, z) {
  const name = getLabel(feature);
  if (!name) return null;
  const spec = PLACE_SPECS[feature.get('kind')] || PLACE_SPECS['locality'];
  if (z < spec.minZ) return null;

  const t = Math.min(1, (z - spec.minZ) / 4);
  const fontSize = Math.round(spec.minSize + t * (spec.maxSize - spec.minSize));

  return new ol.style.Style({
    text: new ol.style.Text({
      text: spec.upper ? name.toUpperCase() : name,
      font: `${spec.weight} ${fontSize}px Arial, sans-serif`,
      fill: new ol.style.Fill({ color: spec.color }),
      stroke: new ol.style.Stroke({ color: 'rgba(255,255,255,0.9)', width: 3 }),
      overflow: true,
      placement: 'point'
    })
  });
}

function buildingStyle(feature, z) {
  if (z < 13) return null;
  const alpha = Math.min(1, 0.4 + (z - 13) * 0.3).toFixed(2);
  const styles = [
    new ol.style.Style({
      fill: new ol.style.Fill({ color: `rgba(217,208,201,${alpha})` }),
      stroke: new ol.style.Stroke({ color: `rgba(188,176,165,${alpha})`, width: 0.75 })
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
      return color ? new ol.style.Style({ fill: new ol.style.Fill({ color }) }) : null;
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
            fill: new ol.style.Fill({ color: '#5d96bc' }),
            stroke: new ol.style.Stroke({ color: 'rgba(255,255,255,0.85)', width: 2 }),
            overflow: true
          })
        })
      ];
    }
    case 'roads': {
      const geomStyle = roadStyles(kind, z);
      const nameStyle = roadNameStyle(feature, z);
      if (!geomStyle && !nameStyle) return null;
      const arr = Array.isArray(geomStyle) ? [...geomStyle] : (geomStyle ? [geomStyle] : []);
      if (nameStyle) arr.push(nameStyle);
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
          fill: new ol.style.Fill({ color: '#444' }),
          stroke: new ol.style.Stroke({ color: 'rgba(255,255,255,0.9)', width: 2.5 }),
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
}

function buildPreloadLayerCheckboxes() {
  preloadLayersContainer.innerHTML = '';
  appendPreloadLayerRow(VECTOR_LAYER_KEY, 'OSM Vector', { recommended: true, checked: true });
  Object.keys(layerMap).forEach((name) => {
    if (layerHasTimeComponent(layerMap[name])) return;
    appendPreloadLayerRow(name, name, { recommended: false, checked: false });
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

  const selected = getSelectedPreloadLayerKeys();
  if (selected.length === 0) {
    showToast('Select at least one layer', 'warning');
    return;
  }

  const maxZoom = parseInt(zoomSlider.value, 10);
  const adminKey = adminKeyInput.value.trim();
  const bbox = { ...pendingBbox };
  const name = preloadNameInput.value.trim() || null;

  if (adminKey) {
    localStorage.setItem('xyz-admin-key', adminKey);
  }

  const xyzLayers = selected.filter((k) => k !== VECTOR_LAYER_KEY);
  const includeVector = selected.includes(VECTOR_LAYER_KEY);

  hidePreloadModal();

  try {
    const resp = await fetch('/preloads', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Key': adminKey },
      body: JSON.stringify({
        name,
        boundingBox: bbox,
        maxZoom: includeVector ? Math.min(maxZoom, VECTOR_MAX_ZOOM) : maxZoom,
        layers: xyzLayers,
        includeVector
      })
    });

    if (resp.status === 202) {
      showToast('Preload started', 'success');
      openDownloadsPanel();
      return;
    }
    if (resp.status === 409) showToast('A preload is already in progress', 'warning');
    else if (resp.status === 401 || resp.status === 403) showToast('Invalid or missing admin key', 'error');
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
    const resp = await fetch('/preloads');
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

    return `<li class="download-item" data-index="${i}"${hint}>
      <div class="download-name">${escapeHtml(p.name || '(unnamed)')}</div>
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
  geoTiffOverlay.classList.remove('hidden');
}

function hideGeoTiffModal() {
  geoTiffOverlay.classList.add('hidden');
}

async function submitGeoTiff() {
  const name = geoTiffNameInput.value.trim();
  const file = geoTiffFileInput.files[0];
  const adminKey = geoTiffAdminKeyInput.value.trim();

  if (!name) {
    geoTiffStatus.textContent = 'Layer name is required.';
    return;
  }
  if (!file) {
    geoTiffStatus.textContent = 'Choose a GeoTIFF file.';
    return;
  }

  if (adminKey) {
    localStorage.setItem('xyz-admin-key', adminKey);
  }

  const fd = new FormData();
  fd.append('name', name);
  fd.append('file', file);

  geoTiffStatus.textContent = 'Uploading and tiling — this may take a while…';
  const submitBtn = document.getElementById('submit-geotiff');
  submitBtn.disabled = true;

  try {
    const resp = await fetch('/layers/geotiff', {
      method: 'POST',
      headers: { 'X-Admin-Key': adminKey },
      body: fd
    });
    if (resp.status === 201) {
      const layer = await resp.json();
      hideGeoTiffModal();
      showToast(`Layer '${layer.name}' created (max zoom ${layer.maxZoom})`, 'success');
      addLayerToSelect(layer);
      switchLayer(layer.name);
      layerSelect.value = layer.name;
      return;
    }
    const text = await resp.text();
    if (resp.status === 401 || resp.status === 403) {
      geoTiffStatus.textContent = 'Invalid or missing admin key.';
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
  layerMap[layer.name] = layer;
  const opt = document.createElement('option');
  opt.value = layer.name;
  opt.textContent = layer.name;
  const vectorOpt = layerSelect.querySelector('option[value="__vector__"]');
  if (vectorOpt) {
    layerSelect.insertBefore(opt, vectorOpt);
  } else {
    layerSelect.appendChild(opt);
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
