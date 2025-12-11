# O11yLite Frontend

An InertiaJS frontend.

* React
* InertiaJS React
* TailwindCSS
* shadcn

## Adding shadcn Components

Use the shadcn CLI to add components:

```bash
# Add a single component
npx shadcn@latest add button

# Add multiple components
npx shadcn@latest add card dialog table

# Add a pre-built block (e.g., sidebar, login form)
npx shadcn@latest add sidebar-01
```

Browse available components at https://ui.shadcn.com/docs/components and blocks at https://ui.shadcn.com/blocks.

Components are installed to `src/components/ui/` and can be customized directly.

## Updating shadcn Components

To pull upstream updates for a component:

```bash
# Update a specific component (will prompt before overwriting)
npx shadcn@latest add button

# Force overwrite without prompting
npx shadcn@latest add button --overwrite
```

Note: shadcn components are copied into your codebase, not installed as dependencies. Local customizations will be lost when using `--overwrite`.

## Testing

Uses Vitest + React Testing Library for page-level testing.

```bash
npm test              # Run tests once
npm run test:watch    # Watch mode for development
npm run test:coverage # Run with coverage report
```

Test files are co-located with pages (e.g., `src/pages/Home.test.tsx`).
