# Resource-constrained acquisition and information views

IB should treat expensive full-page rendering as one possible way to obtain useful information, not as the prerequisite for seeing a result or completing a task.

This work is not primarily desktop versus mobile and is not ordinary reader mode. A phone may need only headings, links, forms, tables, status values, prices, dates, controls, or a small set of images from a much larger web application.

Acquisition and extraction complement renderer adapters; they are not forced through the renderer interface. An extractor may satisfy a task without a live page session. The resulting information view may be painted by a frontend, handed to another action, or later supplied to a renderer. See `docs/personal-workbench.md`.

## Goal

Make the cheapest useful representation or action available first, then spend more only when the task remains unsatisfied.

Possible sources include:

- an existing extracted or response cache;
- a direct image, document, or other resource;
- HTML and its operative content;
- a structured response or source-specific adapter;
- a limited live renderer;
- a full renderer.

This is not necessarily one linear page ladder. A task may consult several cheap sources before deciding that any live renderer is required.

## Pre-paint behavior

As soon as IB has useful material, it should expose it. It should not hold the surface blank until a site's application shell finishes booting.

Useful early material includes:

- title and requested, resolved, and canonical URLs;
- headings, section ancestry, ordinary text, and code blocks;
- links with source context and position;
- forms and form actions;
- tables and definition-list-like data;
- labeled controls;
- important images or thumbnails;
- obvious status, price, date, count, and identifier fields;
- structured metadata already present in HTML.

The first view may be incomplete. Better information may replace or augment it without losing task, source, tab, or history identity.

### arXiv integration demonstration

`bin/prepaint_arxiv_progressively.grease` joins the scientific-media fetch boundary to the Idriç information projection without starting a heavyweight renderer. It selects arXiv HTML first and gives the information core progressive 4, 16, and 32 KiB prefixes.

For arXiv paper `2203.11355`, the live regression requires the title at 4 KiB, the paper heading at 16 KiB, and the abstract heading and opening text at 32 KiB while script text remains absent. It reports complete-document size, bytes admitted at each stage, and zero heavyweight-renderer invocations. A deterministic arXiv-shaped fixture exercises the same boundary without the live network.

## Renderer-neutral extracted representation

The lightweight path produces a source-backed representation, not a pretend complete DOM snapshot.

A first representation may contain:

```text
source_representation_id
requested_url
resolved_url
canonical_url
fetch_time
title
headings_and_section_ancestry
text_blocks_and_source_spans
code_blocks
links_with_context
tables
forms
controls
images
structured_metadata
observed_data_responses
completeness
```

The exact schema can evolve. Stable source references matter because a task summary, classifier, or language model must be able to cite the representation and span from which a claim came.

The current home-grown HTML extraction is an early slice, not the final graph parser. Operative-content selection, relative URL and `<base>` resolution, semantic landmarks, code-block fidelity, and robust source spans may use deterministic parser adapters while Idriç retains typed selection and resource-budget policy.

## Network-response extraction

Large applications often fetch useful data before spending substantially more work constructing interface chrome. When an intelligible response is safe to expose, IB may project it directly as a small table or action surface.

This is an information-acquisition path, not a promise to reverse-engineer every private protocol. Authentication, permissions, origin policy, request partitioning, and secret storage still apply.

## Resource policy

The constrained path may suppress or defer work not needed for the task:

- animations and decorative fonts;
- analytics, telemetry, ads, and tracking;
- autoplay media and video;
- large decorative images;
- expensive canvas or WebGL work;
- background application code unrelated to the result.

JavaScript is an escalation, not an assumption. If source bytes or a structured response already supply the needed information, JavaScript need not run merely because the site normally expects it.

An unsupported input may safely remain unknown, be discarded, or be handed to an available full renderer. Its existence does not create a general compatibility obligation for IB.

## Paint budgeting

Once a renderer has enough display work to predict cost, IB should ask for an
estimate **before** starting the paint.  The estimate is derived renderer state,
not canonical browser state, and it may be specific to the current device,
backend, cache state, and candidate fidelity.

The browser-owned policy compares the estimated full-fidelity paint with the
remaining visual budget.  The initial policy boundary is deliberately small:

1. use full fidelity when its estimate fits;
2. otherwise use a renderer-supplied reduced-fidelity candidate when that fits;
3. otherwise reuse the previous paint when one exists;
4. otherwise defer the visual paint rather than beginning work already predicted
   to miss the budget badly.

This policy does not change layout, source representation, tab identity, or task
state.  A reduced candidate describes visual work only.  The renderer remains
responsible for defining what its reduced candidate means; the browser core does
not encode Android, GPU, CSS-effect, or display-list details as semantic truth.

After an actual paint, the adapter may report predicted and measured durations so
a replaceable estimator can improve on that device/backend.  Those observations
are evidence for future estimates, not durable facts about the page.

`IB.PaintBudget` contains the renderer-neutral stub.  `IB.PaintBudget.Mock`
contains deterministic test-only estimates for policy acceptance; its mock work
classes do not define the eventual estimator feature set.

## Relation to renderer capabilities

Renderer capabilities still govern live page sessions, including HTML, CSS, JavaScript, canvas, media, accessibility trees, and recoverable DOM state.

Acquisition and extraction capabilities are adjacent services rather than brands of renderer. The core or task policy may use them first and attach or hot-swap a renderer later without changing browser-owned identity.

## Cache boundary

Extracted views are valuable pre-paint artifacts but remain disposable unless explicitly promoted.

Clearing cache may remove:

- extracted information views;
- response bodies retained only for pre-paint;
- thumbnails and reduced images;
- renderer recovery material;
- derived extraction indexes and transient summary presentations.

It must not remove durable resources, tabs, history events, task frontiers, user assertions, accepted organization, or the retained last-complete result needed to resume a durable investigation. A later visit may show that retained result immediately while revalidating it; after cache deletion, the durable task still knows what can be regenerated.

## Initial regressions

1. Useful HTML table: expose it without JavaScript or a renderer.
2. Headings, code, operative links, and a form: preserve structure and source context before full rendering.
3. Header, navigation, logout, and token links: do not treat them as the operative documentation frontier.
4. Useful structured response after a small request: expose it without booting the full application.
5. Decorative fonts, analytics, video, and large images: do not block the first useful result.
6. Insufficient extraction: attach a fuller renderer without losing browser or task identity.
7. Cached extracted view: pre-paint immediately and revalidate in the background.
8. Cache cleared: durable task and history survive and the view can be regenerated.
9. Renderer absent or crashed: extracted results remain usable.

## Non-goals for the first slices

- perfect visual reproduction of arbitrary sites;
- preserving a JavaScript heap across renderer swaps;
- automatically understanding every application protocol;
- replacing a full renderer when the task genuinely requires one;
- treating every page as an article;
- turning every discovered candidate into a live tab;
- making a generated summary canonical truth.
