// Global TypeScript augmentation for @testing-library/jest-dom matchers.
// Registering the import here (rather than only in jest.setup.js) makes
// matchers like toBeInTheDocument / toHaveClass / toHaveAttribute visible
// to `tsc --noEmit` across every *.test.tsx file.
import "@testing-library/jest-dom";
