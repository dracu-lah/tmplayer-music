// The Pages entry point: every path comes here (public/_routes.json includes "/*"), and the
// gateway in src/index.ts answers it, a JSON 404 included for any path it does not serve.

import { type Env, serve } from "../src/index";

export const onRequest: PagesFunction<Env> = (context) => serve(context.request, context.env, context);
