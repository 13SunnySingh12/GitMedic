import { setupServer } from 'msw/node';

// Handlers are added per test with server.use(...): no implicit fake API.
export const server = setupServer();
