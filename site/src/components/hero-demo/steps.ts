/*
 * The home hero's story: the agent records a test against Order Desk, saves it as a recorded test, and it replays.
 * Frames are real 2x screenshots of the main window (title band cropped, 2400 x 1448 px): f0 is the app at start,
 * rNN the app after the test's NNth action. Boxes and camera views are in those frame pixels.
 * While recording, the log shows what the agent gives each step to say: one short sentence in the person's words,
 * the way the palette's Steps list reads (steps n, a step's checks n.k). The replay shows the same sentences: the
 * recorded test keeps each one as its step's say.
 */
import type { Box, View } from './stage';

const ALL: View = { x: 0, y: 0, w: 2400 };
const TOP: View = { x: 0, y: 0, w: 1440 };
const CUSTOMER: View = { x: 0, y: 40, w: 1600 };
const PRODUCTS: View = { x: 330, y: 300, w: 1760 };
const CART: View = { x: 1360, y: 250, w: 1040 };
const TOTALS: View = { x: 1360, y: 720, w: 1040 };
const CONFIRM: View = { x: 650, y: 400, w: 1100 };

export type Step = {
	/** act: the step clicks or types (a ripple); check: it asserts (a box that turns green). */
	kind: 'act' | 'check';
	box: Box;
	view: View;
	/** The frame after the step; a check leaves the app as it was. */
	frame?: string;
	/** The step's sentence (its say), as the agent recorded it. */
	say: string;
};

export const steps: Step[] = [
	{
		kind: 'act', box: [0, 0, 67, 42], view: TOP, frame: 'r01',
		say: 'Open a new order',
	},
	{
		kind: 'act', box: [157, 199, 600, 51], view: TOP, frame: 'r02',
		say: 'Type “Ada” into Customer',
	},
	{
		kind: 'act', box: [157, 199, 600, 51], view: CUSTOMER, frame: 'r03',
		say: 'Press Enter to pick Ada Shop Ltd',
	},
	{
		kind: 'check', box: [134, 125, 376, 53], view: CUSTOMER,
		say: 'The New order tab for Ada Shop Ltd is selected',
	},
	{
		kind: 'act', box: [418, 427, 210, 48], view: PRODUCTS, frame: 'r04',
		say: 'Select A4 paper, 500 sheets',
	},
	{
		kind: 'act', box: [1139, 1333, 223, 50], view: PRODUCTS, frame: 'r05',
		say: 'Add the A4 paper to the cart',
	},
	{
		kind: 'act', box: [418, 622, 210, 48], view: PRODUCTS, frame: 'r06',
		say: 'Select Gel pen, black',
	},
	{
		kind: 'act', box: [1139, 1333, 223, 50], view: PRODUCTS, frame: 'r07',
		say: 'Add the gel pen to the cart',
	},
	{
		kind: 'act', box: [1843, 355, 193, 48], view: CART, frame: 'r08',
		say: 'Order 3 packs of A4 paper',
	},
	{
		kind: 'act', box: [1843, 404, 193, 48], view: CART, frame: 'r09',
		say: 'Order 10 gel pens',
	},
	{
		kind: 'check', box: [2196, 355, 182, 48], view: CART,
		say: 'The A4 paper line comes to €14.70',
	},
	{
		kind: 'check', box: [2296, 1122, 88, 34], view: TOTALS,
		say: 'The cart subtotal is €26.70',
	},
	{
		kind: 'check', box: [2296, 1158, 88, 34], view: TOTALS,
		say: 'The volume discount is −€0.60',
	},
	{
		kind: 'check', box: [2310, 1230, 74, 34], view: TOTALS,
		say: 'The VAT is €5.22',
	},
	{
		kind: 'check', box: [2274, 1272, 114, 58], view: TOTALS,
		say: 'The cart total is €31.32',
	},
	// r10: the question "Discard this new order?" open over the window, drawn as the app shows it (the run demo's c10)
	{
		kind: 'act', box: [0, 0, 67, 42], view: TOP, frame: 'r10',
		say: 'Close the order tab',
	},
	{
		kind: 'act', box: [1286, 771, 152, 52], view: CONFIRM, frame: 'f0',
		say: 'Discard the order',
	},
];

export const story = {
	all: ALL,
	// The recorded test's name (uitest/<name>.json): Order Desk's uitest/cart-totals.json, the same 11 steps. The frames
	// come from the CartTotalsUiIT run; the discard step ends on the app as it was at the start (f0).
	test: 'cart-totals',
	firstFrame: 'f0',
	lastFrame: 'f0',
	frames: [...new Set(['f0', ...steps.flatMap((s) => (s.frame ? [s.frame] : []))])],
};
