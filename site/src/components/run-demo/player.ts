/*
 * Plays the IntelliJ page's run demo (steps.ts) in the [data-kc-run] figure RunDemo.astro renders: the test tree
 * and progress of a "UI tests" run on the left, the app on the right (the shared stage) with a ripple where a step
 * acts and a box that turns green where it checks, and the line that runs in the console under both.
 */
import { Clock, Stage, Stopped, controls, reducedMotion } from '../hero-demo/stage';
import { run, tests, type Test } from './steps';

const CONSOLE_LINES = 3;

class Player {
	private readonly clock = new Clock();
	private readonly stage: Stage;
	private readonly console: HTMLElement;
	private readonly status: HTMLElement;
	private readonly progress: HTMLElement;

	constructor(private readonly root: HTMLElement) {
		this.stage = new Stage(root.querySelector('.kc-demo-stage')!, root.dataset.base!, run.all);
		this.console = root.querySelector('.kc-run-console')!;
		this.status = root.querySelector('.kc-run-status')!;
		this.progress = root.querySelector('.kc-run-progress')!;
	}

	start() {
		const sync = controls(this.root, this.clock, () => {
			if (this.root.dataset.state === 'end') this.loop();
		});
		if (reducedMotion()) {
			this.clock.userPaused = true;
			this.showEnd();
		} else {
			this.stage.preload(run.frames);
			this.loop();
		}
		sync();
	}

	private node(id: string) {
		return this.root.querySelector<HTMLElement>(`[data-node="${id}"]`)!;
	}

	private setNode(id: string, state: 'pending' | 'running' | 'passed', time = '') {
		const el = this.node(id);
		el.dataset.state = state;
		el.querySelector('.kc-run-time')!.textContent = time;
	}

	private setProgress(passed: number, running: boolean) {
		this.progress.style.setProperty('--done', String(passed / tests.length));
		this.status.textContent = running
			? `Running · ${passed} of ${tests.length} passed`
			: `Tests passed: ${passed} of ${tests.length}`;
		this.root.dataset.state = running ? 'running' : passed === tests.length ? 'passed' : 'idle';
	}

	private line(text: string, cls = '') {
		const el = document.createElement('div');
		el.className = `kc-demo-line ${cls}`;
		const gutter = document.createElement('span');
		gutter.className = 'kc-demo-gutter';
		const t = document.createElement('span');
		t.textContent = text;
		el.append(gutter, t);
		this.console.append(el);
		while (this.console.children.length > CONSOLE_LINES) this.console.firstElementChild!.remove();
		return { el, gutter };
	}

	private reset() {
		this.stage.clearMarks();
		this.console.replaceChildren();
		this.setNode('root', 'pending');
		for (const t of tests) {
			this.setNode(t.cls, 'pending');
			this.setNode(`${t.cls}#`, 'pending');
		}
		this.setProgress(0, false);
		this.status.textContent = '';
	}

	private async loop() {
		const gen = this.clock.restart();
		try {
			for (;;) await this.play(gen);
		} catch (e) {
			if (!(e instanceof Stopped)) throw e;
		}
	}

	private async play(gen: number) {
		const { stage, clock } = this;
		this.reset();
		stage.aim(run.all);
		await stage.show(run.firstFrame);
		await clock.wait(900, gen);
		this.setNode('root', 'running');
		this.setProgress(0, true);
		this.line(run.agentLine, 'is-out');
		await clock.wait(500, gen);
		let passed = 0;
		for (const test of tests) {
			this.setNode(test.cls, 'running');
			this.setNode(`${test.cls}#`, 'running');
			await this.runTest(test, gen);
			this.setNode(`${test.cls}#`, 'passed', test.time);
			this.setNode(test.cls, 'passed', test.time);
			this.setProgress(++passed, passed < tests.length);
			await clock.wait(500, gen);
		}
		stage.aim(run.all);
		this.setNode('root', 'passed', run.total);
		this.line(`Tests passed: ${tests.length} of ${tests.length}`, 'is-pass').gutter.textContent = '✓';
		await clock.wait(4500, gen);
	}

	private async runTest(test: Test, gen: number) {
		const { stage, clock } = this;
		const t = (ms: number) => clock.wait(ms * test.pace, gen);
		for (const step of test.steps) {
			if (step.view && step.view !== stage.view) {
				stage.aim(step.view);
				await t(650);
			}
			const l = this.line(step.java, step.kind === 'line' ? 'is-out' : 'is-java');
			if (step.kind === 'act') {
				stage.ripple(step.box!);
				await t(200);
				if (step.frame) await stage.show(step.frame);
				// a typed call: its further clicks, on the same line
				for (const part of step.more ?? []) {
					await t(300);
					if (part.view && part.view !== stage.view) {
						stage.aim(part.view);
						await t(650);
					}
					stage.ripple(part.box);
					await t(200);
					if (part.frame) await stage.show(part.frame);
				}
				l.gutter.textContent = '✓';
				await t(420);
			} else if (step.kind === 'check') {
				const box = stage.mark(step.box!, 'is-checking');
				await t(450);
				box.className = 'kc-demo-mark is-passed';
				l.gutter.textContent = '✓';
				await t(750);
				box.remove();
			} else {
				await t(260);
			}
		}
	}

	/** The end of the run, shown at once (reduced motion). */
	private showEnd() {
		this.clock.restart();
		this.reset();
		this.stage.aim(run.all, false);
		this.stage.set(run.lastFrame);
		for (const t of tests) {
			this.setNode(t.cls, 'passed', t.time);
			this.setNode(`${t.cls}#`, 'passed', t.time);
		}
		this.setNode('root', 'passed', run.total);
		this.setProgress(tests.length, false);
		this.line(run.agentLine, 'is-out');
		this.line(`Tests passed: ${tests.length} of ${tests.length}`, 'is-pass').gutter.textContent = '✓';
		this.root.dataset.state = 'end';
	}
}

for (const root of document.querySelectorAll<HTMLElement>('[data-kc-run]')) new Player(root).start();
