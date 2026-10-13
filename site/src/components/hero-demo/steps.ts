/*
 * The home hero's story: the agent records a test against Order Desk, exports it, and the test replays it.
 * Frames are real 2x screenshots of the main window (title band cropped, 2400 x 1448 px): f0 is the app at start,
 * rNN the app after the test's NNth action, saved by the exported test itself while it ran and passed.
 * Boxes and camera views are in those frame pixels. Commands and Java lines are the recorded and exported ones;
 * "…" stands for a scope left out to fit the line.
 */
import type { Box, View } from './stage';

const ALL: View = { x: 0, y: 0, w: 2400 };
const TOP: View = { x: 0, y: 0, w: 1440 };
const CUSTOMER: View = { x: 0, y: 40, w: 1600 };
const PRODUCTS: View = { x: 330, y: 300, w: 1760 };
const CART: View = { x: 1360, y: 250, w: 1040 };
const TOTALS: View = { x: 1360, y: 720, w: 1040 };

export type Step = {
	/** act: the step clicks or types (a ripple); check: it asserts (a box that turns green). */
	kind: 'act' | 'check';
	box: Box;
	view: View;
	/** The frame after the step; a check leaves the app as it was. */
	frame?: string;
	command: string;
	java: string;
};

const ADA = 'ui.tab("New order · Ada Shop Ltd")';

export const steps: Step[] = [
	{
		kind: 'act', box: [0, 0, 67, 42], view: TOP, frame: 'r01',
		command: `menu '{"path":["File","New order"]}'`,
		java: 'ui.menu("File", "New order");',
	},
	{
		kind: 'act', box: [157, 199, 600, 51], view: TOP, frame: 'r02',
		command: `type '{"target":{…"name":"Customer"},"text":"Ada"}'`,
		java: 'ui.tab("New order").field("Customer").type("Ada");',
	},
	{
		kind: 'act', box: [157, 199, 600, 51], view: CUSTOMER, frame: 'r03',
		command: `press '{"keys":"Enter"}'`,
		java: 'ui.press("Enter");',
	},
	{
		kind: 'check', box: [134, 125, 376, 53], view: CUSTOMER,
		command: `expect '{"target":{"role":"tab","name":"New order · Ada Shop Ltd"},"state":["selected"]}'`,
		java: `assertThat(${ADA}).isSelected();`,
	},
	{
		kind: 'act', box: [418, 427, 210, 48], view: PRODUCTS, frame: 'r04',
		command: `click '{"target":{…"row":{"Product":"A4 paper, 500 sheets"},"column":"SKU"}}'`,
		java: '….table().row("Product", "A4 paper, 500 sheets").cell("SKU").click();',
	},
	{
		kind: 'act', box: [1139, 1333, 223, 50], view: PRODUCTS, frame: 'r05',
		command: `click '{"target":{…"role":"button","name":"Add to cart"}}'`,
		java: '….button("Add to cart").click();',
	},
	{
		kind: 'act', box: [418, 622, 210, 48], view: PRODUCTS, frame: 'r06',
		command: `click '{"target":{…"row":{"Product":"Gel pen, black"},"column":"SKU"}}'`,
		java: '….table().row("Product", "Gel pen, black").cell("SKU").click();',
	},
	{
		kind: 'act', box: [1139, 1333, 223, 50], view: PRODUCTS, frame: 'r07',
		command: `click '{"target":{…"role":"button","name":"Add to cart"}}'`,
		java: '….button("Add to cart").click();',
	},
	{
		kind: 'act', box: [1843, 355, 193, 48], view: CART, frame: 'r08',
		command: `type '{"target":{…"A4 paper, 500 sheets"},"column":"Qty"},"text":"3","commit":true}'`,
		java: '….row("Product", "A4 paper, 500 sheets").cell("Qty").type("3", null, Boolean.TRUE);',
	},
	{
		kind: 'act', box: [1843, 404, 193, 48], view: CART, frame: 'r09',
		command: `type '{"target":{…"Gel pen, black"},"column":"Qty"},"text":"10","commit":true}'`,
		java: '….row("Product", "Gel pen, black").cell("Qty").type("10", null, Boolean.TRUE);',
	},
	{
		kind: 'check', box: [2196, 355, 182, 48], view: CART,
		command: `expect '{…"cell":{"row":{"Product":"A4 paper, 500 sheets"},"column":"Line total","expected":"€14.70"}}'`,
		java: 'assertThat(….cell("Line total")).hasCell(row("Product", "A4 paper, 500 sheets"), "Line total", "€14.70");',
	},
	{
		kind: 'check', box: [2274, 1272, 114, 58], view: TOTALS,
		command: `expect '{"target":{…"name":"€31.32"},"text":"€31.32"}'`,
		java: 'assertThat(….label("€31.32")).hasText("€31.32");',
	},
];

export const story = {
	all: ALL,
	testClass: 'CartTotalsUiIT',
	firstFrame: 'f0',
	lastFrame: 'r09',
	frames: ['f0', ...steps.flatMap((s) => (s.frame ? [s.frame] : []))],
};
