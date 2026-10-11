// Installed before page scripts, in every frame. The bridge only accepts playback state.
(function () {
    if (window.__hqlAudioInstalled || !window.HqlAudio) return;
    window.__hqlAudioInstalled = true;
    const id = Date.now().toString(36) + Math.random().toString(36).slice(2);
    const contexts = new Set();
    const pausedMedia = new Map();
    const pausedContexts = new Set();
    let blocked = false;
    let interrupted = false;
    let changing = false;
    let gone = false;
    let last = false;
    const media = () => Array.from(document.querySelectorAll('audio,video'));
    const identity = m => m.currentSrc || m.src || '';
    function update() {
        if (gone) return;
        const audible = media().some(m => !m.paused && !m.ended && !m.muted && m.volume > 0)
            || Array.from(contexts).some(c => c.state === 'running');
        // Keep the native focus request while our own temporary pause is in effect.
        const active = audible || (interrupted && (pausedMedia.size > 0 || pausedContexts.size > 0));
        if (!!active !== last) {
            last = !!active;
            HqlAudio.postMessage((last ? 'play:' : 'idle:') + id);
        }
    }
    function pause(permanent) {
        if (gone || (!permanent && (blocked || interrupted))) return;
        if (permanent) {
            blocked = true;
            interrupted = false;
            pausedMedia.clear();
            pausedContexts.clear();
        } else interrupted = true;
        changing = true;
        try {
            media().forEach(m => {
                if (!permanent && !m.paused && !m.ended) pausedMedia.set(m, identity(m));
                m.pause();
            });
            contexts.forEach(c => {
                if (c.state === 'running') {
                    if (!permanent) pausedContexts.add(c);
                    c.suspend().catch(() => {});
                }
            });
        } finally { changing = false; }
        update();
    }
    function resume() {
        if (gone || blocked || !interrupted) return;
        interrupted = false;
        const elements = Array.from(pausedMedia.entries());
        const audioContexts = Array.from(pausedContexts);
        pausedMedia.clear();
        pausedContexts.clear();
        elements.forEach(([m, src]) => {
            if (m.isConnected !== false && !m.ended && identity(m) === src) m.play().catch(() => {});
        });
        audioContexts.forEach(c => { if (c.state === 'suspended') c.resume().catch(() => {}); });
        update();
    }
    HqlAudio.onmessage = event => {
        if (event.data === 'pause') pause(true);
        else if (event.data === 'suspend') pause(false);
        else if (event.data === 'resume') resume();
    };
    ['pointerdown', 'keydown'].forEach(name => document.addEventListener(name, e => {
        if (!e.isTrusted) return;
        blocked = false;
        if (!pausedMedia.size && !pausedContexts.size) interrupted = false;
    }, true));
    const play = HTMLMediaElement.prototype.play;
    const stop = HTMLMediaElement.prototype.pause;
    HTMLMediaElement.prototype.play = function () {
        if (gone || blocked || interrupted) return Promise.reject(new DOMException('Audio focus lost', 'AbortError'));
        return play.apply(this, arguments);
    };
    HTMLMediaElement.prototype.pause = function () {
        // A user/site pause must cancel resumption, even if the element is already paused.
        if (!changing) pausedMedia.delete(this);
        const result = stop.apply(this, arguments);
        update();
        return result;
    };
    ['playing', 'pause', 'ended', 'volumechange', 'emptied'].forEach(name =>
        document.addEventListener(name, event => {
            if (!changing && event && (name === 'ended' || name === 'emptied')) pausedMedia.delete(event.target);
            update();
        }, true));
    ['AudioContext', 'webkitAudioContext'].forEach(name => {
        const Original = window[name];
        if (!Original) return;
        const start = Original.prototype.resume;
        const suspend = Original.prototype.suspend;
        Original.prototype.resume = function () {
            if (gone || blocked || interrupted) return Promise.reject(new DOMException('Audio focus lost', 'AbortError'));
            return start.apply(this, arguments);
        };
        Original.prototype.suspend = function () {
            if (!changing) pausedContexts.delete(this);
            const result = suspend.apply(this, arguments);
            update();
            return result;
        };
        window[name] = new Proxy(Original, {construct(target, args) {
            const context = Reflect.construct(target, args);
            contexts.add(context);
            context.addEventListener('statechange', () => {
                if (context.state === 'closed') {
                    contexts.delete(context);
                    pausedContexts.delete(context);
                }
                update();
            });
            if (blocked || interrupted) context.suspend().catch(() => {});
            else update();
            return context;
        }});
    });
    const timer = setInterval(update, 500);
    window.addEventListener('pagehide', () => {
        gone = true;
        interrupted = false;
        pausedMedia.clear();
        pausedContexts.clear();
        clearInterval(timer);
        last = false;
        HqlAudio.postMessage('idle:' + id);
    });
})();
