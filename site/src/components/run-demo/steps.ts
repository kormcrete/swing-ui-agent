/*
 * The IntelliJ page's run demo: the "UI tests" run configuration runs Order Desk's two @UiTest classes, by class
 * name, against the running app. Frames are real 2x screenshots from one such run (title band cropped, 2400 x 1448):
 * cNN after the NNth click of CartTotalsUiIT (taken when it still spelled out the clicks its typed calls make now: one
 * call is one line, its further clicks the ripples and frames of Step.more), pNN after PlaceOrderUiIT's steps (its own
 * screenshots where it takes them), c00 the app at start. A dialog open at that moment is drawn, with a title bar, over the main window as it was then,
 * centred on the window as the app shows it. Times are from a run without the extra screenshots. Java lines are the
 * tests' own; "…" stands for a part left out to fit the line.
 */
import type { Box, View } from '../hero-demo/stage';

const ALL: View = { x: 0, y: 0, w: 2400 };
const TOP: View = { x: 0, y: 0, w: 1200 };
const CUSTOMER: View = { x: 0, y: 60, w: 1200 };
const TERMS: View = { x: 0, y: 60, w: 1920 };
const PRODUCTS: View = { x: 380, y: 390, w: 1600 };
const CART: View = { x: 1360, y: 250, w: 1040 };
const TOTALS: View = { x: 1360, y: 720, w: 1040 };
const DIALOG_TOP: View = { x: 500, y: 124, w: 1400 };
const DIALOG_BOTTOM: View = { x: 500, y: 560, w: 1400 };
const CONFIRM: View = { x: 650, y: 400, w: 1100 };
const ORDER: View = { x: 0, y: 60, w: 1400 };
const STATUS_BAR: View = { x: 0, y: 698, w: 1200 };
const ORDERS_ROW: View = { x: 1000, y: 200, w: 1400 };

const FILE: Box = [0, 0, 67, 42];
const CUSTOMER_FIELD: Box = [157, 199, 600, 51];
const ADA_TAB: Box = [134, 125, 376, 53];
const A4_SKU: Box = [418, 427, 210, 48];
const PEN_SKU: Box = [418, 622, 210, 48];
const ADD: Box = [1139, 1333, 223, 50];
const QTY_A4: Box = [1843, 355, 193, 48];
const QTY_PEN: Box = [1843, 404, 193, 48];
const NEXT: Box = [1558, 1236, 144, 56];

export type Step = {
	/** act: a ripple where it acts; check: a box that turns green; line: only the console line (a screenshot). */
	kind: 'act' | 'check' | 'line';
	box?: Box;
	view?: View;
	/** The frame after the step. */
	frame?: string;
	/** An act line that does several things (a typed call): each further ripple, with its view and frame. */
	more?: { box: Box; view?: View; frame?: string }[];
	java: string;
};

export type Test = { cls: string; method: string; time: string; pace: number; steps: Step[] };

const ADA = 'ui.tab("New order · Ada Shop Ltd")';
const shot = (window: string, file: string): Step => ({
	kind: 'line',
	java: `ui.inWindow("${window}").saveScreenshot("PlaceOrderUiIT/${file}.png", 2);`,
});

export const tests: Test[] = [
	{
		cls: 'CartTotalsUiIT', method: 'totalsTheCart()', time: '703 ms', pace: 0.8,
		steps: [
			{
				kind: 'act', box: FILE, view: TOP, frame: 'c01', java: 'openNewOrder(ui, "Ada");',
				more: [{ box: CUSTOMER_FIELD, frame: 'c02' }, { box: CUSTOMER_FIELD, view: CUSTOMER, frame: 'c03' }],
			},
			{ kind: 'check', box: ADA_TAB, view: CUSTOMER, java: `assertThat(${ADA}).isSelected();` },
			{
				kind: 'act', box: A4_SKU, view: PRODUCTS, frame: 'c04', java: 'addQtyToCart(ui, "A4 paper, 500 sheets", 3);',
				more: [{ box: ADD, frame: 'c05' }, { box: QTY_A4, view: CART }],
			},
			{
				kind: 'act', box: PEN_SKU, view: PRODUCTS, frame: 'c06', java: 'addQtyToCart(ui, "Gel pen, black", 10);',
				more: [{ box: ADD, frame: 'c07' }, { box: QTY_PEN, view: CART, frame: 'c09' }],
			},
			{ kind: 'check', box: [2196, 355, 182, 48], view: CART, java: 'assertThat(….cell("Line total")).hasCell(row("Product", "A4 paper, 500 sheets"), "Line total", "€14.70");' },
			{ kind: 'check', box: [2274, 1272, 114, 58], view: TOTALS, java: 'cartTotalIs(ui, "€31.32");' },
			{ kind: 'act', box: FILE, view: TOP, frame: 'c10', java: 'ui.menu("File", "Close tab");' },
			{ kind: 'act', box: [1286, 771, 152, 52], view: CONFIRM, frame: 'c11', java: 'ui.answer("Discard");' },
			{ kind: 'check', box: [112, 108, 420, 70], view: TOP, java: `assertThat(${ADA}).isAbsent();` },
		],
	},
	{
		cls: 'PlaceOrderUiIT', method: 'placesAnOrderForAda()', time: '3 s 446 ms', pace: 0.5,
		steps: [
			{ kind: 'act', box: FILE, view: TOP, frame: 'p01', java: 'ui.menu("File", "New order");' },
			{ kind: 'check', box: [134, 125, 196, 53], view: TOP, java: 'assertThat(ui.tab("New order")).isSelected();' },
			shot('main', '01-new-order'),
			{ kind: 'act', box: CUSTOMER_FIELD, view: TOP, frame: 'c02', java: 'ui.tab("New order").field("Customer").type("Ada");' },
			{ kind: 'act', box: CUSTOMER_FIELD, view: CUSTOMER, frame: 'p02', java: 'ui.press("Enter");' },
			{ kind: 'check', box: ADA_TAB, view: CUSTOMER, java: `assertThat(${ADA}).isSelected();` },
			{ kind: 'check', box: [781, 199, 1055, 51], view: TERMS, java: 'assertThat(….label("Account · Invoice 30 days · Credit limit €2,000.00").contains()).isVisible();' },
			shot('main', '02-customer'),
			{ kind: 'act', box: A4_SKU, view: PRODUCTS, frame: 'c04', java: '….table().row("Product", "A4 paper, 500 sheets").cell("SKU").click();' },
			{ kind: 'act', box: ADD, view: PRODUCTS, frame: 'c05', java: '….button("Add to cart").click();' },
			{ kind: 'act', box: PEN_SKU, view: PRODUCTS, frame: 'c06', java: '….table().row("Product", "Gel pen, black").cell("SKU").click();' },
			{ kind: 'act', box: ADD, view: PRODUCTS, frame: 'c07', java: '….button("Add to cart").click();' },
			{ kind: 'act', box: QTY_A4, view: CART, frame: 'c08', java: '….row("Product", "A4 paper, 500 sheets").cell("Qty").type("3", null, Boolean.TRUE);' },
			{ kind: 'act', box: QTY_PEN, view: CART, frame: 'p03', java: '….row("Product", "Gel pen, black").cell("Qty").type("10", null, Boolean.TRUE);' },
			{ kind: 'check', box: [2296, 1122, 88, 34], view: TOTALS, java: 'assertThat(….label("Subtotal")).hasText("€26.70");' },
			{ kind: 'check', box: [2296, 1158, 88, 34], view: TOTALS, java: 'assertThat(….label("Volume discount")).hasText("−€0.60");' },
			{ kind: 'check', box: [2310, 1230, 74, 34], view: TOTALS, java: 'assertThat(….label("VAT (20%)")).hasText("€5.22");' },
			{ kind: 'check', box: [2274, 1272, 114, 58], view: TOTALS, java: 'assertThat(….label("Total")).hasText("€31.32");' },
			shot('main', '03-cart'),
			{ kind: 'act', box: [2180, 1333, 197, 50], view: TOTALS, frame: 'p04', java: '….button("Checkout…").click();' },
			{ kind: 'check', box: [540, 652, 644, 56], view: DIALOG_TOP, java: 'assertThat(ui.dialog().field("Postal code")).hasValue("10119");' },
			shot('dialog', '04-address'),
			{ kind: 'act', box: NEXT, view: DIALOG_BOTTOM, frame: 'p05', java: 'ui.dialog().button("Next").click();' },
			{ kind: 'check', box: [540, 348, 420, 44], view: DIALOG_TOP, java: 'assertThat(ui.dialog().radio("Standard · 3–5 business days")).isChecked();' },
			shot('dialog', '05-shipping'),
			{ kind: 'act', box: NEXT, view: DIALOG_BOTTOM, frame: 'p06', java: 'ui.dialog().button("Next").click();' },
			{ kind: 'check', box: [536, 1244, 272, 44], view: DIALOG_BOTTOM, java: 'assertThat(ui.dialog().label("Enter the PO number")).isVisible();' },
			{ kind: 'check', box: NEXT, view: DIALOG_BOTTOM, java: 'assertThat(ui.dialog().button("Next")).isDisabled();' },
			shot('dialog', '06-payment'),
			{ kind: 'act', box: [606, 468, 594, 56], view: DIALOG_TOP, java: 'ui.dialog().field("PO number").type("PO-7781");' },
			{ kind: 'act', box: NEXT, view: DIALOG_BOTTOM, frame: 'p07', java: 'ui.dialog().button("Next").click();' },
			{ kind: 'check', box: [1752, 1136, 112, 50], view: DIALOG_BOTTOM, java: 'assertThat(ui.dialog().label("Total")).hasText("€37.20");' },
			shot('dialog', '07-review'),
			{ kind: 'act', box: [1490, 1236, 212, 56], view: DIALOG_BOTTOM, frame: 'p08', java: 'ui.dialog().button("Place order").click();' },
			shot('dialog', '08-confirm'),
			{ kind: 'act', box: [1241, 775, 196, 56], view: CONFIRM, frame: 'p09', java: 'ui.answer("Place order");' },
			{ kind: 'check', box: [12, 1412, 280, 32], view: STATUS_BAR, java: 'String order = eventually("the status bar naming the new order", …);' },
			{ kind: 'check', box: [134, 125, 193, 53], view: ORDER, java: 'assertThat(ui.tab(order)).isSelected();' },
			shot('main', '09-placed'),
			{ kind: 'act', box: [12, 118, 124, 62], view: ORDER, frame: 'p10', java: 'ui.tab("Orders").click();' },
			{ kind: 'check', box: [2170, 321, 206, 47], view: ORDERS_ROW, java: 'assertThat(….cell("Status")).hasCell(row("Order", order), "Status", "Placed");' },
			{ kind: 'check', box: [1963, 321, 204, 47], view: ORDERS_ROW, java: 'assertThat(….cell("Status")).hasCell(row("Order", order), "Total", "€37.20");' },
			shot('main', '10-orders-after'),
		],
	},
];

export const run = {
	all: ALL,
	config: 'UI tests',
	total: '4 s 149 ms',
	seconds: '4.1 s',
	agentLine: '[ui] 127.0.0.1:8770 agent 0.1.0-SNAPSHOT built …; app started …; plugin ready',
	firstFrame: 'c00',
	lastFrame: 'p10',
	frames: ['c00', ...new Set(tests.flatMap((t) => t.steps.flatMap((s) =>
		[s.frame, ...(s.more ?? []).map((m) => m.frame)].filter((f): f is string => !!f))))],
};
