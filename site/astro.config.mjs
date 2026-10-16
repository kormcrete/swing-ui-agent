// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';
import starlightLlmsTxt from 'starlight-llms-txt';

const repo = 'https://github.com/kormcrete/swing-ui-agent';

export default defineConfig({
	site: 'https://kormcrete.github.io',
	base: '/swing-ui-agent',
	// Literal quotes stay straight: tool descriptions quote values an agent copies ('created', "escape").
	markdown: { smartypants: false },
	integrations: [
		starlight({
			title: 'swing-ui-agent',
			description:
				'Let a coding agent drive your real Swing app and write its UI tests. Attach one jar, no changes to the app.',
			favicon: '/favicon.svg',
			// agents fetching a page find the plain-text docs here: they live under the base, not at the domain's root
			head: [
				{
					tag: 'link',
					attrs: { rel: 'alternate', type: 'text/plain', href: '/swing-ui-agent/llms.txt', title: 'The docs as plain text, for coding agents' },
				},
			],
			social: [{ icon: 'github', label: 'GitHub', href: repo }],
			editLink: { baseUrl: `${repo}/edit/main/site/` },
			customCss: [
				'@fontsource/figtree/700.css',
				'@fontsource/figtree/800.css',
				'@fontsource/nunito-sans/400.css',
				'@fontsource/nunito-sans/600.css',
				'@fontsource/nunito-sans/700.css',
				'@fontsource/jetbrains-mono/400.css',
				'./src/styles/kormcrete.css',
			],
			expressiveCode: {
				themes: ['github-dark-default', 'github-light-default'],
				styleOverrides: {
					borderRadius: '10px',
					codeFontFamily: 'var(--__sl-font-mono)',
					frames: { frameBoxShadowCssValue: 'none' },
				},
			},
			components: {
				Footer: './src/components/Footer.astro',
				Hero: './src/components/Hero.astro',
			},
			plugins: [
				starlightLinksValidator(),
				starlightLlmsTxt({
					projectName: 'swing-ui-agent',
					description:
						'A Java agent (-javaagent) that lets a coding agent drive a real Java Swing app from a shell or over MCP, and records what it did as JUnit 5 @UiTest classes or as recorded tests (JSON files of steps and checks that run without compiling). No changes to the app; no java.awt.Robot.',
					details: [
						'- Coding agents: the skills in the repository (`skills/`) teach the drive, target and record workflow; install them rather than working from these docs alone.',
						'- Two kinds of test: a recorded test is exact visible steps and checks that a person or the agent records and anyone runs; a Java test is for anything dynamic. Start at https://kormcrete.github.io/swing-ui-agent/start/choose-test-kind/.',
						'- Every tool and its parameters: https://kormcrete.github.io/swing-ui-agent/reference/tools/ (generated from the agent\'s own schemas; `java -jar <agent jar> tools <name>` prints the same from a running app).',
					].join('\n'),
					optionalLinks: [
						{ label: 'README', url: `${repo}#readme`, description: 'Quick start, status and build' },
						{ label: 'Skills', url: `${repo}/tree/main/skills`, description: 'Agent skills: swing-ui-record-test, swing-ui-targeting, swing-ui-driver' },
					],
				}),
			],
			sidebar: [
				{ label: 'For developers', items: [{ autogenerate: { directory: 'start' } }] },
				{ label: 'Order Desk demo', items: [{ autogenerate: { directory: 'demo' } }] },
				{ label: 'Guides', items: [{ autogenerate: { directory: 'guides' } }] },
				{ label: 'AI agents', items: [{ autogenerate: { directory: 'agents' } }] },
				{ label: 'Plugins', items: [{ autogenerate: { directory: 'plugins' } }] },
				{ label: 'Reference', items: [{ autogenerate: { directory: 'reference' } }] },
				{ label: 'Project', items: [{ autogenerate: { directory: 'project' } }] },
			],
		}),
	],
});
