/*
 * Plays the hero story (steps.ts) in the [data-kc-demo] figure that Hero.astro renders: the stage (stage.ts) with a
 * ripple where a step acts and a box where it checks, and a three-line log under it. It pauses with its button,
 * while the figure is off screen and while the tab is hidden; with reduced motion it shows the end state until the
 * button is pressed.
 */
import { Clock, Stage, Stopped, controls, reducedMotion } from './stage';
import { steps, story } from './steps';

const LOG_LINES = 3;

class Player {
	private readonly clock = new Clock();
	private readonly stage: Stage;
	private readonly badge: HTMLElement;
	private readonly log: HTMLElement;

	constructor(private readonly root: HTMLElement) {
		this.stage = new Stage(root.querySelector('.kc-demo-stage')!, root.dataset.base!, story.all);
		this.badge = root.querySelector('.kc-demo-badge')!;
		this.log = root.querySelector('.kc-demo-log')!;
	}

	start() {
		const sync = controls(this.root, this.clock, () => {
			if (this.root.dataset.state === 'end') this.loop();
		});
		if (reducedMotion()) {
			this.clock.userPaused = true;
			this.showEnd();
		} else {
			this.stage.preload(story.frames);
			this.loop();
		}
		sync();
	}

	/** A log line: a gutter (● recorded, ✓ passed), a prompt, the text; long text is cut at the end, not the gutter. */
	private line(prompt: string, text: string, cls = '') {
		const el = document.createElement('div');
		el.className = `kc-demo-line ${cls}`;
		const gutter = document.createElement('span');
		gutter.className = 'kc-demo-gutter';
		const p = document.createElement('span');
		p.className = 'kc-demo-prompt';
		p.textContent = prompt;
		const t = document.createElement('span');
		t.className = 'kc-demo-text';
		t.textContent = text;
		el.append(gutter, p, t);
		this.log.append(el);
		while (this.log.children.length > LOG_LINES) this.log.firstElementChild!.remove();
		return { el, text: t, gutter };
	}

	/** Types text into a log line over ms of playing time, however long the text. */
	private async typeInto(target: HTMLElement, text: string, ms: number, gen: number) {
		const chars = [...text];
		const step = 40;
		for (let t = step; t < ms; t += step) {
			target.textContent = chars.slice(0, Math.ceil((chars.length * t) / ms)).join('');
			await this.clock.wait(step, gen);
		}
		target.textContent = text;
	}

	private setBadge(text: string, state: string) {
		this.badge.textContent = text;
		this.root.dataset.state = state;
	}

	private reset() {
		this.stage.clearMarks();
		this.log.replaceChildren();
	}

	private async loop() {
		const gen = this.clock.restart();
		try {
			for (;;) {
				await this.record(gen);
				await this.replay(gen);
			}
		} catch (e) {
			if (!(e instanceof Stopped)) throw e;
		}
	}

	private async record(gen: number) {
		const { stage, clock } = this;
		this.reset();
		this.setBadge('● Recording', 'record');
		stage.aim(story.all);
		await stage.show(story.firstFrame);
		const reset = this.line('$UI', '');
		await this.typeInto(reset.text, `session '{"op":"reset"}'`, 300, gen);
		await clock.wait(700, gen);
		let n = 0;
		for (const step of steps) {
			if (step.view !== stage.view) {
				stage.aim(step.view);
				await clock.wait(600, gen);
			}
			const l = this.line('$UI', '');
			await this.typeInto(l.text, step.command, 360, gen);
			if (step.kind === 'act') {
				stage.ripple(step.box);
				await clock.wait(140, gen);
				if (step.frame) await stage.show(step.frame);
			} else {
				const box = stage.mark(step.box, 'is-noted');
				await clock.wait(600, gen);
				box.remove();
			}
			l.gutter.textContent = '●';
			n++;
			this.setBadge(`● Recording · ${n} step${n === 1 ? '' : 's'}`, 'record');
			await clock.wait(300, gen);
		}
		stage.aim(story.all);
		const l = this.line('$UI', '');
		await this.typeInto(l.text, `export_test '{"class":"${story.testClass}"}'`, 300, gen);
		await clock.wait(250, gen);
		this.line('', `wrote src/test/java/…/${story.testClass}.java`, 'is-out');
		await clock.wait(1600, gen);
	}

	private async replay(gen: number) {
		const { stage, clock } = this;
		this.reset();
		this.setBadge(`▶ ${story.testClass}`, 'replay');
		await stage.show(story.firstFrame);
		this.line('', `mvn -Pui verify  ·  ${story.testClass}`, 'is-out');
		await clock.wait(800, gen);
		for (const step of steps) {
			if (step.view !== stage.view) {
				stage.aim(step.view);
				await clock.wait(500, gen);
			}
			const l = this.line('', step.java, 'is-java');
			if (step.kind === 'act') {
				stage.ripple(step.box);
				await clock.wait(120, gen);
				if (step.frame) await stage.show(step.frame);
				l.gutter.textContent = '✓';
				await clock.wait(280, gen);
			} else {
				const box = stage.mark(step.box, 'is-checking');
				await clock.wait(400, gen);
				box.className = 'kc-demo-mark is-passed';
				l.gutter.textContent = '✓';
				await clock.wait(800, gen);
				box.remove();
			}
		}
		stage.aim(story.all);
		this.line('', 'Tests run: 1, Failures: 0', 'is-pass').gutter.textContent = '✓';
		this.line('', 'In CI the same test runs headless on every push.', 'is-out');
		this.setBadge(`✓ ${story.testClass} passed`, 'passed');
		await clock.wait(4000, gen);
	}

	/** The end of a replay, shown at once (reduced motion). */
	private showEnd() {
		this.clock.restart();
		this.reset();
		this.stage.aim(story.all, false);
		this.stage.set(story.lastFrame);
		const last = steps.filter((s) => s.kind === 'check').at(-1)!;
		this.line('', last.java, 'is-java').gutter.textContent = '✓';
		this.line('', 'Tests run: 1, Failures: 0', 'is-pass').gutter.textContent = '✓';
		this.line('', 'In CI the same test runs headless on every push.', 'is-out');
		this.setBadge(`✓ ${story.testClass} passed`, 'end');
	}
}

for (const root of document.querySelectorAll<HTMLElement>('[data-kc-demo]')) new Player(root).start();
