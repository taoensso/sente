# Official Sente reference example

This example dives into Sente's full functionality pretty quickly; it's probably more useful as a reference than a tutorial.

Please see Sente's [top-level README](https://github.com/taoensso/sente) for a gentler introduction to Sente.

## Instructions

### Without REPL

1. Call `lein start` at your terminal.
2. This will start a local HTTP server and auto-open a test page in your web browser.
3. Follow the instructions from that page.

### With REPL

1. Call `lein start-dev` at your terminal.
2. This will start a local [nREPL server](https://nrepl.org/nrepl/index.html) and print the server's details, e.g.:

  > nREPL server started on port 61332 on host 127.0.0.1 - nrepl://127.0.0.1:61332
2. This will start a local HTTP server and auto-open a test page in your web browser.
3. Follow the instructions from that page.

3. Connect your dev environment to that nREPL server, e.g. `(cider-connect)` from Emacs.
4. Open the example's [`server.clj`](https://github.com/taoensso/sente/blob/master/example-project/src/example/server.clj) file in your dev environment.
5. Eval `(example.server/start!)` to start a local HTTP server and auto-open a test page in your web browser.
6. Follow the instructions from that page.