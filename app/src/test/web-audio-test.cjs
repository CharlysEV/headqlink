const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../main/assets/web_audio.js'), 'utf8');

function document() {
    const messages = [], events = new Map();
    let poll;
    const emit = (name, target) => events.get(name)?.({ target });
    class Media {
        constructor() { this.paused = true; this.ended = false; this.muted = false; this.volume = 1; this.src = 'stream-one'; }
        play() { this.paused = false; emit('playing', this); return Promise.resolve(); }
        pause() { this.paused = true; emit('pause', this); }
    }
    class AudioContext {
        constructor() { this.state = 'suspended'; }
        addEventListener(name, callback) { this.changed = callback; }
        resume() { this.state = 'running'; this.changed(); return Promise.resolve(); }
        suspend() { this.state = 'suspended'; this.changed(); return Promise.resolve(); }
    }
    const media = new Media();
    const bridge = { postMessage: message => messages.push(message) };
    const window = { HqlAudio: bridge, AudioContext, addEventListener: (name, fn) => events.set(name, fn) };
    vm.runInNewContext(source, {
        window, HqlAudio: bridge, HTMLMediaElement: Media, DOMException,
        document: { querySelectorAll: () => [media], addEventListener: (name, fn) => events.set(name, fn) },
        setInterval: callback => { poll = callback; return 1; }, clearInterval: () => {},
    });
    return { media, messages, events, window, poll: () => poll(), send: data => bridge.onmessage({ data }) };
}

async function run() {
    {
        const d = document();
        await d.media.play();
        assert.match(d.messages.at(-1), /^play:/);
        d.poll();
        assert.equal(d.messages.length, 1, 'repeated polls must not reacquire focus');
        d.send('pause');
        assert.equal(d.media.paused, true);
        assert.match(d.messages.at(-1), /^idle:/);
        // headqlink: tras perder el foco, pedir play vuelve a reproducir (antes se rechazaba hasta tocar la página y,
        // en el coche, los vídeos se quedaban parados para siempre).
        await d.media.play();
        assert.equal(d.media.paused, false);
        assert.match(d.messages.at(-1), /^play:/);
    }
    {
        const d = document();
        await d.media.play();
        d.send('suspend');
        assert.equal(d.media.paused, true);
        assert.equal(d.messages.length, 1, 'temporary pause must retain native focus rather than report idle');
        d.send('resume');
        assert.equal(d.media.paused, false);
        assert.equal(d.messages.length, 1, 'gain must resume without a new focus request');
    }
    {
        const d = document();
        await d.media.play();
        d.send('suspend');
        d.media.pause();
        assert.match(d.messages.at(-1), /^idle:/, 'manual pause cancels temporary playback intent');
        d.send('resume');
        assert.equal(d.media.paused, true, 'gain must not override a manual pause');
    }
    {
        const d = document();
        await d.media.play();
        d.send('suspend');
        d.send('pause');
        d.send('resume');
        assert.equal(d.media.paused, true, 'permanent loss while suspended must cancel resume');
        await d.media.play();
        assert.equal(d.media.paused, false, 'an explicit play after the loss plays again');
    }
    {
        const d = document();
        await d.media.play();
        d.send('suspend');
        d.media.src = 'stream-two';
        d.send('resume');
        assert.equal(d.media.paused, true, 'do not start a different source automatically');
    }
    {
        const d = document();
        const ctx = new d.window.AudioContext();
        await ctx.resume();
        d.send('suspend');
        assert.equal(ctx.state, 'suspended');
        assert.equal(d.messages.length, 1, 'Web Audio suspension must retain focus');
        d.send('resume');
        assert.equal(ctx.state, 'running');
        d.send('suspend');
        await ctx.suspend();
        assert.match(d.messages.at(-1), /^idle:/);
        d.send('resume');
        assert.equal(ctx.state, 'suspended', 'manual Web Audio suspension cancels resume');
    }
    {
        const d = document();
        const ctx = new d.window.AudioContext();
        await ctx.resume();
        d.send('pause');
        await ctx.resume();
        assert.equal(ctx.state, 'running', 'an explicit resume after the loss plays again');
        d.send('suspend');
        d.events.get('pagehide')();
        assert.match(d.messages.at(-1), /^idle:/);
        d.send('resume');
        assert.equal(ctx.state, 'suspended', 'old documents must not resume after navigation');
    }
    console.log('PASS web audio: permanent/transient focus, manual pause, source changes, Web Audio and navigation');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
