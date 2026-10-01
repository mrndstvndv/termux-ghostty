import { FileDiff, parsePatchFiles, parseDiffFromFile, type FileDiffMetadata } from '@pierre/diffs';

interface RenderOptions {
  isDark?: boolean;
  diffStyle?: 'split' | 'unified';
  showLineNumbers?: boolean;
  isWordDiffEnabled?: boolean;
  fontSize?: string;
  fontFamily?: string;
  useCustomFont?: boolean;
  fontUrl?: string;
}

interface AndroidBridge {
  onRenderComplete?: (fileCount: number, hunkCount: number) => void;
  onError?: (errorMessage: string) => void;
  onFileClick?: (fileName: string) => void;
  onHorizontalScrollState?: (canScrollLeft: boolean, canScrollRight: boolean) => void;
}

declare global {
  interface Window {
    AndroidDiffBridge?: AndroidBridge;
    diffViewer: {
      renderPatch: (patchString: string, optionsJson?: string) => Promise<void>;
      renderFiles: (
        oldContent: string | null,
        newContent: string | null,
        filename: string,
        optionsJson?: string
      ) => Promise<void>;
      updateTheme: (isDark: boolean, bgColor?: string, fgColor?: string) => void;
      setFontFamily: (useCustomFont: boolean, fontUrl?: string) => void;
      setDiffStyle: (style: 'split' | 'unified') => void;
      setLineNumbers: (show: boolean) => void;
      setWordDiff: (enabled: boolean) => void;
      collapseAll: () => void;
      expandAll: () => void;
      toggleAll: () => void;
      toggleFile: (index: number) => void;
      setFileCollapsed: (index: number, collapsed: boolean) => void;
      clear: () => void;
    };
  }
}

let activeFileDiffInstances: FileDiff[] = [];
let currentOptions: RenderOptions = {
  isDark: true,
  diffStyle: 'unified',
  showLineNumbers: true,
  isWordDiffEnabled: true,
};

let lastRenderType: 'patch' | 'files' | null = null;
let lastPatchString: string = '';
let lastOldContent: string | null = null;
let lastNewContent: string | null = null;
let lastFilename: string = '';

const rootElement = document.getElementById('diff-root') || document.body;

function setCustomFont(enabled: boolean, fontUrl?: string) {
  currentOptions.useCustomFont = enabled;
  if (fontUrl !== undefined) {
    currentOptions.fontUrl = fontUrl;
  }

  let styleEl = document.getElementById('diffs-custom-font') as HTMLStyleElement | null;
  if (enabled) {
    if (!styleEl) {
      styleEl = document.createElement('style');
      styleEl.id = 'diffs-custom-font';
      document.head.appendChild(styleEl);
    }
    const resolvedUrl = (fontUrl && fontUrl.length > 0)
      ? fontUrl
      : (currentOptions.fontUrl || '/custom-font/font.ttf');
    styleEl.textContent = `
      @font-face {
        font-family: 'AppCustomFont';
        src: url('${resolvedUrl}');
        font-display: swap;
      }
    `;
    document.documentElement.style.setProperty(
      '--diffs-font-family',
      "'AppCustomFont', ui-monospace, SFMono-Regular, \"SF Mono\", Menlo, Consolas, \"Liberation Mono\", monospace"
    );
    document.documentElement.style.setProperty(
      '--diffs-header-font-family',
      "'AppCustomFont', system-ui, -apple-system, \"Segoe UI\", Roboto, \"Helvetica Neue\", \"Noto Sans\", \"Liberation Sans\", Arial, sans-serif"
    );
  } else {
    if (styleEl) {
      styleEl.remove();
    }
    document.documentElement.style.removeProperty('--diffs-font-family');
    document.documentElement.style.removeProperty('--diffs-header-font-family');
  }
}

function showMessage(text: string, isError = false) {
  rootElement.innerHTML = `
    <div style="
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: 180px;
      padding: 32px 16px;
      color: ${isError ? '#ff6762' : 'var(--diffs-fg-number, #888)'};
      font-family: var(--diffs-font-family, monospace);
      font-size: 13px;
      text-align: center;
    ">
      <p style="margin: 0;">${text}</p>
    </div>
  `;
}

function createDiffInstance(options: RenderOptions): FileDiff {
  return new FileDiff({
    theme: {
      dark: 'pierre-dark',
      light: 'pierre-light',
    },
    themeType: options.isDark ? 'dark' : 'light',
    diffStyle: options.diffStyle || 'unified',
    disableLineNumbers: options.showLineNumbers === false,
    lineDiffType: options.isWordDiffEnabled === false ? 'none' : 'word',
    overflow: 'scroll',
    preferredHighlighter: 'shiki-js',
    tokenizeMaxLineLength: 1000,
    renderHeaderPrefix: () => {
      const chevron = document.createElement('span');
      chevron.className = 'diff-collapse-chevron';
      chevron.setAttribute('aria-hidden', 'true');
      chevron.innerHTML = `
        <svg viewBox="0 0 16 16" width="13" height="13" fill="currentColor">
          <path fill-rule="evenodd" d="M1.646 4.646a.5.5 0 0 1 .708 0L8 10.293l5.646-5.647a.5.5 0 0 1 .708.708l-6 6a.5.5 0 0 1-.708 0l-6-6a.5.5 0 0 1 0-.708z"/>
        </svg>
      `;
      return chevron;
    },
    unsafeCSS: `
      [data-code] {
        touch-action: pan-x pan-y !important;
        -webkit-overflow-scrolling: touch !important;
      }
      [data-column-number], [data-gutter], [data-gutter-buffer] {
        touch-action: pan-x pan-y !important;
      }
      [data-diffs-header] {
        cursor: pointer !important;
        user-select: none !important;
        -webkit-user-select: none !important;
        -webkit-tap-highlight-color: transparent !important;
        transition: background-color 0.15s ease, top 0.2s ease !important;
      }
      [data-diffs-header][data-sticky] {
        top: var(--diffs-header-top, 0px) !important;
      }
      [data-diffs-header]:active {
        background-color: var(--diffs-header-active-bg, rgba(255, 255, 255, 0.08)) !important;
      }
      :host([data-collapsed="true"]) pre,
      [data-collapsed="true"] pre {
        display: none !important;
      }
    `,
  });
}

function setFileCollapsed(item: HTMLElement, collapsed: boolean) {
  const diffsContainer = (
    item.tagName.toLowerCase() === 'diffs-container'
      ? item
      : item.querySelector('diffs-container')
  ) as HTMLElement | null;
  const anyContainer = diffsContainer as any;

  if (collapsed) {
    item.setAttribute('data-collapsed', 'true');
    diffsContainer?.setAttribute('data-collapsed', 'true');
  } else {
    item.removeAttribute('data-collapsed');
    diffsContainer?.removeAttribute('data-collapsed');
  }

  const pre = anyContainer?.pre || diffsContainer?.shadowRoot?.querySelector('pre');
  if (pre) {
    pre.style.display = collapsed ? 'none' : '';
  }

  const notices = item.querySelectorAll<HTMLElement>('.diff-empty-notice');
  for (const notice of notices) {
    notice.style.display = collapsed ? 'none' : '';
  }

  const chevrons = (diffsContainer || item).querySelectorAll('.diff-collapse-chevron');
  for (const chevron of chevrons) {
    if (collapsed) {
      chevron.setAttribute('data-collapsed', 'true');
    } else {
      chevron.removeAttribute('data-collapsed');
    }
  }
}

function setupCollapsible(_instance: FileDiff, _container: HTMLElement) {
  // Collapse is handled by the delegated document-level click listener
  // installed in installCollapseDelegation(). Per-header listeners are
  // intentionally NOT used here: @pierre/diffs recreates the header DOM
  // inside its shadow root on every rerender (theme change, word-diff
  // toggle, shiki highlight pass), which would orphan listeners attached
  // to the old header element and silently break tap-to-collapse.
}

/**
 * Single delegated tap-to-collapse handler. Survives rerenders because it
 * lives on `document` instead of the (recreated) header elements. Clicks
 * inside the header's shadow DOM still reach `document` because `click`
 * is a composed event; `composedPath()` reveals the original inner target.
 */
function installCollapseDelegation() {
  document.addEventListener('click', (e: MouseEvent) => {
    const target = e.target as HTMLElement | null;
    if (target?.closest?.('a, button, input, select, textarea')) {
      return;
    }
    const path: EventTarget[] =
      typeof e.composedPath === 'function' ? e.composedPath() : [];
    let header: HTMLElement | null = null;
    for (const node of path) {
      if (node instanceof HTMLElement && node.hasAttribute('data-diffs-header')) {
        header = node;
        break;
      }
    }
    if (!header) return;
    let item: HTMLElement | null = null;
    for (const node of path) {
      if (node instanceof HTMLElement && node.classList?.contains('file-diff-item')) {
        item = node;
        break;
      }
    }
    if (!item) {
      item = target?.closest?.('.file-diff-item') as HTMLElement | null;
    }
    if (!item) return;
    setFileCollapsed(item, !item.hasAttribute('data-collapsed'));
  });
}

installCollapseDelegation();

/**
 * Horizontal scroll-edge reporting for tab-swipe handoff.
 *
 * Code scrolling lives inside inner elements ([data-code] inside the
 * <diffs-container> shadow root) while html/body has overflow-x: hidden,
 * so native WebView.canScrollHorizontally() always reports false. Detect
 * the active scrollable container in JS (piercing shadow roots via
 * composedPath + getRootNode().host) and push canScrollLeft/Right to the
 * native DiffBridge. Native uses this to decide whether a horizontal drag
 * scrolls code or is handed to the parent HorizontalPager as a tab swipe.
 */
function findHorizontalScrollable(target: EventTarget | null): HTMLElement | null {
  let el = target as HTMLElement | null;
  while (el && el !== document.body && el !== document.documentElement) {
    if (el instanceof HTMLElement && el.scrollWidth > el.clientWidth + 2) {
      try {
        const style = window.getComputedStyle(el);
        const overflowX = style.overflowX;
        if (overflowX === 'auto' || overflowX === 'scroll') {
          return el;
        }
      } catch {
        // getComputedStyle can throw on detached nodes; keep walking up.
      }
    }
    const root = (el as HTMLElement).getRootNode?.() as ShadowRoot | null;
    const host = root?.host as HTMLElement | null;
    el = ((el as HTMLElement).parentElement ?? host) as HTMLElement | null;
  }
  return null;
}

let currentScrollable: HTMLElement | null = null;

// Only report while a touch is active. Reporting after touchend (e.g. from
// momentum scroll events) would race the next gesture's reset on the native
// side and hand it stale bounds.
let touchActive = false;

function updateHorizontalScrollState() {
  if (!touchActive) return;
  if (!currentScrollable) {
    window.AndroidDiffBridge?.onHorizontalScrollState?.(false, false);
    return;
  }
  const scrollLeft = currentScrollable.scrollLeft;
  const maxScroll = currentScrollable.scrollWidth - currentScrollable.clientWidth;
  const canScrollLeft = scrollLeft > 1;
  const canScrollRight = scrollLeft < maxScroll - 1;
  window.AndroidDiffBridge?.onHorizontalScrollState?.(canScrollLeft, canScrollRight);
}

function installHorizontalScrollState() {
  window.addEventListener('touchstart', (e: TouchEvent) => {
    const path = typeof e.composedPath === 'function' ? e.composedPath() : [];
    const target = (path.length > 0 ? path[0] : e.target) as EventTarget | null;
    touchActive = true;
    currentScrollable = findHorizontalScrollable(target);
    updateHorizontalScrollState();
  }, { passive: true });

  // capture: true catches scroll events from inner shadow-DOM scrollers,
  // which do not bubble past the shadow boundary by default.
  window.addEventListener('scroll', () => {
    updateHorizontalScrollState();
  }, { capture: true, passive: true });

  const clearScrollable = () => {
    touchActive = false;
    currentScrollable = null;
  };
  window.addEventListener('touchend', clearScrollable, { passive: true });
  window.addEventListener('touchcancel', clearScrollable, { passive: true });
}

installHorizontalScrollState();

let resetScrollHeaderFn: (() => void) | null = null;

function installScrollHeaderHiding() {
  let lastScrollY = window.scrollY || 0;
  let isHeaderCollapsed = false;
  const SCROLL_THRESHOLD = 8;

  const setHeaderTop = (top: string) => {
    document.documentElement.style.setProperty('--diffs-header-top', top);
  };

  resetScrollHeaderFn = () => {
    isHeaderCollapsed = false;
    lastScrollY = window.scrollY || 0;
    setHeaderTop('0px');
  };

  window.addEventListener(
    'scroll',
    () => {
      const currentScrollY = window.scrollY || 0;
      const delta = currentScrollY - lastScrollY;

      if (currentScrollY <= 20) {
        if (isHeaderCollapsed) {
          isHeaderCollapsed = false;
          setHeaderTop('0px');
        }
        lastScrollY = currentScrollY;
        return;
      }

      if (Math.abs(delta) < SCROLL_THRESHOLD) {
        return;
      }

      if (delta > 0 && currentScrollY > 50) {
        if (!isHeaderCollapsed) {
          isHeaderCollapsed = true;
          setHeaderTop('-80px');
        }
      } else if (delta < 0) {
        if (isHeaderCollapsed) {
          isHeaderCollapsed = false;
          setHeaderTop('0px');
        }
      }

      lastScrollY = currentScrollY;
    },
    { passive: true }
  );
}

installScrollHeaderHiding();

function reapplyCollapsedStates() {
  const items = rootElement.querySelectorAll<HTMLElement>('.file-diff-item');
  for (const item of items) {
    if (item.hasAttribute('data-collapsed')) {
      setFileCollapsed(item, true);
    }
  }
}

function collapseAll() {
  const items = rootElement.querySelectorAll<HTMLElement>('.file-diff-item');
  for (const item of items) {
    setFileCollapsed(item, true);
  }
}

function expandAll() {
  const items = rootElement.querySelectorAll<HTMLElement>('.file-diff-item');
  for (const item of items) {
    setFileCollapsed(item, false);
  }
}

function toggleAll() {
  const items = rootElement.querySelectorAll<HTMLElement>('.file-diff-item');
  let anyExpanded = false;
  for (const item of items) {
    if (!item.hasAttribute('data-collapsed')) {
      anyExpanded = true;
      break;
    }
  }
  for (const item of items) {
    setFileCollapsed(item, anyExpanded);
  }
}

function toggleFile(index: number) {
  const items = rootElement.querySelectorAll<HTMLElement>('.file-diff-item');
  if (index >= 0 && index < items.length) {
    const item = items[index];
    const isCollapsed = item.hasAttribute('data-collapsed');
    setFileCollapsed(item, !isCollapsed);
  }
}

function setFileCollapsedByIndex(index: number, collapsed: boolean) {
  const items = rootElement.querySelectorAll<HTMLElement>('.file-diff-item');
  if (index >= 0 && index < items.length) {
    setFileCollapsed(items[index], collapsed);
  }
}

function cleanupActiveInstances() {
  for (const instance of activeFileDiffInstances) {
    try {
      instance.cleanUp?.();
    } catch {
      // ignore
    }
  }
  activeFileDiffInstances = [];
  rootElement.innerHTML = '';
}

async function renderPatch(patchString: string, optionsJson?: string) {
  resetScrollHeaderFn?.();
  lastRenderType = 'patch';
  lastPatchString = patchString;
  if (optionsJson) {
    try {
      const parsed = JSON.parse(optionsJson);
      currentOptions = { ...currentOptions, ...parsed };
      if (typeof parsed.useCustomFont === 'boolean') {
        setCustomFont(parsed.useCustomFont, parsed.fontUrl);
      }
    } catch {
      // ignore
    }
  }

  cleanupActiveInstances();

  if (!patchString || patchString.trim() === '') {
    showMessage('No changes detected in file.');
    window.AndroidDiffBridge?.onRenderComplete?.(0, 0);
    return;
  }

  try {
    const patches = parsePatchFiles(patchString, undefined, false);
    if (!patches || patches.length === 0) {
      if (/^Binary files .+ differ\s*$/m.test(patchString) || /^GIT binary patch/m.test(patchString)) {
        showMessage('Binary file changed (diff not available).');
        window.AndroidDiffBridge?.onRenderComplete?.(1, 0);
        return;
      }
      showMessage('No patch hunks found.');
      window.AndroidDiffBridge?.onRenderComplete?.(0, 0);
      return;
    }

    let totalFiles = 0;
    let totalHunks = 0;

    for (const patch of patches) {
      for (const fileDiff of patch.files) {
        totalFiles++;
        const hunkCount = fileDiff.hunks?.length || 0;
        totalHunks += hunkCount;

        const instance = createDiffInstance(currentOptions);
        activeFileDiffInstances.push(instance);

        const container = document.createElement('div');
        container.className = 'file-diff-item';
        container.style.marginBottom = '16px';
        rootElement.appendChild(container);

        await instance.render({
          fileDiff,
          containerWrapper: container,
        });

        if (hunkCount === 0) {
          const isBinary = /^Binary files .+ differ\s*$/m.test(patchString) || /^GIT binary patch/m.test(patchString);
          const notice = document.createElement('div');
          notice.className = 'diff-empty-notice';
          notice.style.padding = '16px';
          notice.style.color = 'var(--diffs-fg-number, #888)';
          notice.style.fontFamily = 'var(--diffs-font-family, monospace)';
          notice.style.fontSize = '12px';
          notice.textContent = isBinary
            ? 'Binary file changed (diff not available).'
            : 'No content changes (mode change or empty file).';
          container.appendChild(notice);
        }

        setupCollapsible(instance, container);
      }
    }

    applyLineNumbersToDOM(currentOptions.showLineNumbers !== false);
    window.AndroidDiffBridge?.onRenderComplete?.(totalFiles, totalHunks);
  } catch (err: any) {
    console.error('Failed to parse or render patch:', err);
    showMessage(`Failed to render diff: ${err?.message || err}`, true);
    window.AndroidDiffBridge?.onError?.(String(err?.message || err));
  }
}

async function renderFiles(
  oldContent: string | null,
  newContent: string | null,
  filename: string,
  optionsJson?: string
) {
  resetScrollHeaderFn?.();
  lastRenderType = 'files';
  lastOldContent = oldContent;
  lastNewContent = newContent;
  lastFilename = filename;

  if (optionsJson) {
    try {
      const parsed = JSON.parse(optionsJson);
      currentOptions = { ...currentOptions, ...parsed };
      if (typeof parsed.useCustomFont === 'boolean') {
        setCustomFont(parsed.useCustomFont, parsed.fontUrl);
      }
    } catch {
      // ignore
    }
  }

  cleanupActiveInstances();

  if (oldContent == null && newContent == null) {
    showMessage('No content available for diff.');
    window.AndroidDiffBridge?.onRenderComplete?.(0, 0);
    return;
  }

  try {
    const oldFile = oldContent != null ? { name: filename, contents: oldContent } : null;
    const newFile = newContent != null ? { name: filename, contents: newContent } : null;

    let fileDiff: FileDiffMetadata;
    try {
      fileDiff = parseDiffFromFile(oldFile, newFile, undefined, false);
    } catch {
      // If parsing fails (e.g. both files empty or identical), fallback
      showMessage('No changes detected between file versions.');
      window.AndroidDiffBridge?.onRenderComplete?.(0, 0);
      return;
    }

    const instance = createDiffInstance(currentOptions);
    activeFileDiffInstances.push(instance);

    const container = document.createElement('div');
    container.className = 'file-diff-item';
    rootElement.appendChild(container);

    await instance.render({
      fileDiff,
      containerWrapper: container,
    });

    setupCollapsible(instance, container);

    applyLineNumbersToDOM(currentOptions.showLineNumbers !== false);
    window.AndroidDiffBridge?.onRenderComplete?.(1, fileDiff.hunks?.length || 0);
  } catch (err: any) {
    console.error('Failed to render files diff:', err);
    showMessage(`Failed to render diff: ${err?.message || err}`, true);
    window.AndroidDiffBridge?.onError?.(String(err?.message || err));
  }
}

function updateTheme(isDark: boolean, bgColor?: string, fgColor?: string) {
  currentOptions.isDark = isDark;
  document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
  document.documentElement.style.colorScheme = isDark ? 'dark' : 'light';

  if (bgColor) {
    document.documentElement.style.setProperty('--diffs-bg', bgColor);
  }

  if (fgColor) {
    document.documentElement.style.setProperty('--diffs-fg', fgColor);
    document.body.style.color = fgColor;
  } else {
    document.body.style.color = isDark ? '#e0e0e0' : '#1a1a1a';
  }

  for (const instance of activeFileDiffInstances) {
    try {
      instance.setThemeType?.(isDark ? 'dark' : 'light');
    } catch {
      // ignore
    }
  }
}

function applyLineNumbersToDOM(show: boolean) {
  // 1. Update options and pre attributes on active FileDiff instances
  for (const instance of activeFileDiffInstances) {
    try {
      const anyInst = instance as any;
      if (anyInst.options) {
        anyInst.options.disableLineNumbers = !show;
      }
      if (anyInst.appliedPreAttributes) {
        anyInst.appliedPreAttributes.disableLineNumbers = !show;
      }
      if (typeof anyInst.mergeOptions === 'function') {
        anyInst.mergeOptions({ disableLineNumbers: !show });
      }
      const pre = anyInst.pre || anyInst.fileContainer?.shadowRoot?.querySelector('pre');
      if (pre) {
        if (!show) {
          pre.setAttribute('data-disable-line-numbers', '');
        } else {
          pre.removeAttribute('data-disable-line-numbers');
        }
      }
    } catch (e) {
      console.warn('Failed to update line numbers on FileDiff instance:', e);
    }
  }

  // 2. Direct DOM update on all diffs-containers in rootElement
  const containers = rootElement.querySelectorAll('diffs-container');
  for (const container of containers) {
    const pre = container.shadowRoot?.querySelector('pre');
    if (pre) {
      if (!show) {
        pre.setAttribute('data-disable-line-numbers', '');
      } else {
        pre.removeAttribute('data-disable-line-numbers');
      }
    }
  }

  // 3. Direct DOM update on any .file-diff-item shadow hosts
  const items = rootElement.querySelectorAll('.file-diff-item');
  for (const item of items) {
    for (const child of item.children) {
      const pre = child.shadowRoot?.querySelector('pre');
      if (pre) {
        if (!show) {
          pre.setAttribute('data-disable-line-numbers', '');
        } else {
          pre.removeAttribute('data-disable-line-numbers');
        }
      }
    }
  }

  // 4. Fallback for any pre tags directly in rootElement
  const pres = rootElement.querySelectorAll('pre');
  for (const pre of pres) {
    if (!show) {
      pre.setAttribute('data-disable-line-numbers', '');
    } else {
      pre.removeAttribute('data-disable-line-numbers');
    }
  }
}

function setDiffStyle(style: 'split' | 'unified') {
  if (currentOptions.diffStyle === style) return;
  currentOptions.diffStyle = style;
  const scrollX = window.scrollX;
  const scrollY = window.scrollY;
  for (const instance of activeFileDiffInstances) {
    try {
      const anyInst = instance as any;
      if (anyInst.options) {
        anyInst.options.diffStyle = style;
      }
      if (typeof anyInst.mergeOptions === 'function') {
        anyInst.mergeOptions({ diffStyle: style });
      }
      anyInst.rerender?.();
    } catch {
      // ignore
    }
  }
  reapplyCollapsedStates();
  requestAnimationFrame(() => {
    window.scrollTo(scrollX, scrollY);
  });
}

function setLineNumbers(show: boolean) {
  currentOptions.showLineNumbers = show;
  applyLineNumbersToDOM(show);
}

function setWordDiff(enabled: boolean) {
  if (currentOptions.isWordDiffEnabled === enabled) return;
  currentOptions.isWordDiffEnabled = enabled;
  const lineDiffType = enabled ? 'word' : 'none';
  const scrollX = window.scrollX;
  const scrollY = window.scrollY;
  for (const instance of activeFileDiffInstances) {
    try {
      const anyInst = instance as any;
      if (anyInst.options) {
        anyInst.options.lineDiffType = lineDiffType;
      }
      if (typeof anyInst.mergeOptions === 'function') {
        anyInst.mergeOptions({ lineDiffType });
      }
      anyInst.rerender?.();
    } catch {
      // ignore
    }
  }
  reapplyCollapsedStates();
  requestAnimationFrame(() => {
    window.scrollTo(scrollX, scrollY);
  });
}

function clear() {
  resetScrollHeaderFn?.();
  cleanupActiveInstances();
  lastRenderType = null;
  lastPatchString = '';
  lastOldContent = null;
  lastNewContent = null;
  lastFilename = '';
}

window.diffViewer = {
  renderPatch,
  renderFiles,
  updateTheme,
  setFontFamily: setCustomFont,
  setDiffStyle,
  setLineNumbers,
  setWordDiff,
  collapseAll,
  expandAll,
  toggleAll,
  toggleFile,
  setFileCollapsed: setFileCollapsedByIndex,
  clear,
};
