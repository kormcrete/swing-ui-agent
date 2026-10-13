/*
 * What the hero demo and the run demo share: a stage that shows real 2x screenshots of Order Desk (2400 x 1448 px
 * after the title band is cropped) through a camera, cross-fades between them and draws marks on them, and a clock
 * that only runs while the demo plays. Boxes and views are in frame pixels.
 */

export const FRAME = { w: 2400, h: 1448 };

/** A camera view: the part of the frame the stage shows, always 16:10. */
export type View = { x: number; y: number; w: number };
export type Box = [x: number, y: number, w: number, h: number];

export class Stopped extends Error {}

/** Playing time: wait() counts only while the demo is on screen, in a visible tab and not paused. */
export class Clock {
	generation = 0;
	userPaused = false;
	offscreen = false;

	get running() {
		return !this.userPaused && !this.offscreen && !document.hidden;
	}

	/** A new run of the story; waits of the previous one reject with Stopped. */
	restart() {
		return ++this.generation;
	}

	wait(ms: number, gen = this.generation) {
		return new Promise<void>((resolve, reject) => {
			let last = performance.now();
			const tick = (now: number) => {
				if (gen !== this.generation) return reject(new Stopped());
				if (this.running) ms -= now - last;
				last = now;
				if (ms <= 0) resolve();
				else requestAnimationFrame(tick);
			};
			requestAnimationFrame(tick);
		});
	}
}

export class Stage {
	private readonly cam: HTMLElement;
	private readonly layers: HTMLImageElement[];
	private readonly marks: HTMLElement;
	private front = 0;
	view: View;

	constructor(
		private readonly stage: HTMLElement,
		private readonly base: string,
		initial: View,
	) {
		this.cam = stage.querySelector('.kc-demo-cam')!;
		this.layers = [...stage.querySelectorAll<HTMLImageElement>('.kc-demo-frame')];
		this.marks = stage.querySelector('.kc-demo-marks')!;
		this.cam.style.setProperty('--kc-demo-frame-w', `${FRAME.w}px`);
		this.cam.style.setProperty('--kc-demo-frame-h', `${FRAME.h}px`);
		this.view = initial;
		new ResizeObserver(() => this.aim(this.view, false)).observe(stage);
		this.aim(initial, false);
	}

	src(frame: string) {
		return `${this.base}${frame}.webp`;
	}

	preload(frames: string[]) {
		for (const f of frames) new Image().src = this.src(f);
	}

	/** Points the camera at a view; the CSS transition moves it. */
	aim(view: View, animate = true) {
		this.view = view;
		const s = this.stage.clientWidth / view.w;
		this.cam.style.transition = animate ? '' : 'none';
		this.cam.style.setProperty('--s', String(s));
		this.cam.style.transform = `scale(${s}) translate(${-view.x}px, ${-view.y}px)`;
	}

	/** Cross-fades to a frame once it has loaded. */
	async show(frame: string) {
		const url = this.src(frame);
		if (this.layers[this.front].getAttribute('src') === url) return;
		const next = this.layers[1 - this.front];
		next.src = url;
		await next.decode().catch(() => undefined);
		next.classList.add('is-front');
		this.layers[this.front].classList.remove('is-front');
		this.front = 1 - this.front;
	}

	/** Shows a frame at once, without a fade (reduced motion). */
	set(frame: string) {
		this.layers[this.front].src = this.src(frame);
	}

	mark(box: Box, cls: string) {
		const [x, y, w, h] = box;
		const el = document.createElement('span');
		el.className = `kc-demo-mark ${cls}`;
		Object.assign(el.style, { left: `${x}px`, top: `${y}px`, width: `${w}px`, height: `${h}px` });
		this.marks.append(el);
		return el;
	}

	/** An action: a ring spreading from the control, which removes itself. */
	ripple(box: Box) {
		const el = this.mark(box, 'is-act');
		el.addEventListener('animationend', () => el.remove(), { once: true });
	}

	clearMarks() {
		this.marks.replaceChildren();
	}
}

/** Wires a play/pause button and the off-screen pause to a clock; returns a function that syncs the button. */
export function controls(root: HTMLElement, clock: Clock, onPlay: () => void) {
	const toggle = root.querySelector<HTMLButtonElement>('.kc-demo-toggle')!;
	const sync = () => {
		root.dataset.paused = String(clock.userPaused);
		toggle.setAttribute('aria-label', clock.userPaused ? 'Play the demo' : 'Pause the demo');
		toggle.setAttribute('aria-pressed', String(clock.userPaused));
	};
	toggle.addEventListener('click', () => {
		clock.userPaused = !clock.userPaused;
		if (!clock.userPaused) onPlay();
		sync();
	});
	new IntersectionObserver(([e]) => (clock.offscreen = !e.isIntersecting)).observe(root);
	return sync;
}

export const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
