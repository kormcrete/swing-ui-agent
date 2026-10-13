import { defineCollection } from 'astro:content';
import { z } from 'astro/zod';
import { docsLoader } from '@astrojs/starlight/loaders';
import { docsSchema } from '@astrojs/starlight/schema';

export const collections = {
	docs: defineCollection({
		loader: docsLoader(),
		// heroFacts: a one-line HTML fact strip shown under a splash page's hero buttons; heroDemo: the record-and-replay
		// demo in place of the hero image (see components/Hero.astro)
		schema: docsSchema({
			extend: z.object({ heroFacts: z.string().optional(), heroDemo: z.boolean().optional() }),
		}),
	}),
};
