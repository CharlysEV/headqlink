// Installed before page scripts, in every frame. The bridge only accepts playback state.
(function () {
    if (window.__hqlAudioInstalled || !window.HqlAudio) return;
    window.__hqlAudioInstalled = true;
    const id = Date.now().toString(36) + Math.random().toString(36).slice(2);
    const contexts = new Set();
    let blocked = false;
    let last = false;
    const media = () => Array.from(document.querySelectorAll('audio,video'));
    function update() {
        const active = media().some(m => !m.paused && !m.ended && !m.muted && m.volume > 0)
            || Array.from(contexts).some(c => c.state === 'running');
        if (active !== last) {
            last = active;
            HqlAudio.postMessage((active ? 'play:' : 'idle:') + id);
        }
    }
    function pause() {
        blocked = true;
        media().forEach(m => m.pause());
        contexts.forEach(c => { if (c.state === 'running') c.suspend().catch(() => {}); });
        update();
    }
    HqlAudio.onmessage = event => { if (event.data === 'pause') pause(); };
    ['pointerdown', 'keydown'].forEach(name => document.addEventListener(name, e => {
        if (e.isTrusted) blocked = false;
    }, true));
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
        if (blocked) return Promise.reject(new DOMException('Audio focus lost', 'AbortError'));
        return play.apply(this, arguments);
    };
    ['playing', 'pause', 'ended', 'volumechange', 'emptied'].forEach(name =>
        document.addEventListener(name, update, true));
    ['AudioContext', 'webkitAudioContext'].forEach(name => {
        const Original = window[name];
        if (!Original) return;
        const resume = Original.prototype.resume;
        Original.prototype.resume = function () {
            if (blocked) return Promise.reject(new DOMException('Audio focus lost', 'AbortError'));
            return resume.apply(this, arguments);
        };
        window[name] = new Proxy(Original, {construct(target, args) {
            const context = Reflect.construct(target, args);
            contexts.add(context);
            context.addEventListener('statechange', () => {
                if (context.state === 'closed') contexts.delete(context);
                update();
            });
            if (blocked) context.suspend().catch(() => {});
            else update();
            return context;
        }});
    });
    const timer = setInterval(update, 500);
    window.addEventListener('pagehide', () => {
        clearInterval(timer);
        last = false;
        HqlAudio.postMessage('idle:' + id);
    });
})();
