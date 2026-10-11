const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../main/assets/web_audio.js'), 'utf8');

async function run() {
    const messages = [];
    const events = new Map();
    let poll;
    let media;
    class Media {
        constructor() { this.paused = true; this.ended = false; this.muted = false; this.volume = 1; }
        play() { this.paused = false; events.get('playing')(); return Promise.resolve(); }
        pause() { this.paused = true; events.get('pause')(); }
    }
    class AudioContext {
        constructor() { this.state = 'suspended'; }
        addEventListener(name, callback) { this.changed = callback; }
        resume() { this.state = 'running'; this.changed(); return Promise.resolve(); }
        suspend() { this.state = 'suspended'; this.changed(); return Promise.resolve(); }
    }
    const bridge = { postMessage: message => messages.push(message) };
    const window = { HqlAudio: bridge, AudioContext, addEventListener: (name, fn) => events.set(name, fn) };
    const sandbox = {
        window, HqlAudio: bridge, HTMLMediaElement: Media, DOMException,
        document: { querySelectorAll: () => [media], addEventListener: (name, fn) => events.set(name, fn) },
        setInterval: callback => { poll = callback; return 1; }, clearInterval: () => {},
    };
    media = new Media();
    vm.runInNewContext(source, sandbox);
    await media.play();
    assert.match(messages.at(-1), /^play:/);
    poll();
    assert.equal(messages.length, 1, 'buffering or repeated polls must not reacquire focus');
    bridge.onmessage({ data: 'pause' });
    assert.equal(media.paused, true);
    assert.match(messages.at(-1), /^idle:/);
    await assert.rejects(media.play(), /Audio focus lost/);
    events.get('pointerdown')({ isTrusted: false });
    await assert.rejects(media.play(), /Audio focus lost/);
    events.get('pointerdown')({ isTrusted: true });
    await media.play();
    assert.match(messages.at(-1), /^play:/);
    media.pause();
    const ctx = new window.AudioContext();
    await ctx.resume();
    assert.match(messages.at(-1), /^play:/);
    bridge.onmessage({ data: 'pause' });
    assert.equal(ctx.state, 'suspended', 'Web Audio must also stop when another app takes focus');
    await assert.rejects(ctx.resume(), /Audio focus lost/);
    events.get('keydown')({ isTrusted: true });
    await ctx.resume();
    events.get('pagehide')();
    assert.match(messages.at(-1), /^idle:/);
    console.log('PASS web audio: playback, focus loss, explicit resume, Web Audio and document teardown');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
