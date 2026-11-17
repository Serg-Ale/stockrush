import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { client } from '../api/client.gen';
import { provideHeyApiClient } from '../api/client/client.gen';
import { AuthService } from '../auth/auth.service';
import { fakeToken } from '../auth/testing';
import { OrderStreamService, STREAM_STALL_MS } from './order-stream.service';

const API = 'http://localhost';

/** A fake SSE endpoint: every call to fetch opens a stream the test writes to and closes by hand. */
function fakeServer(status = 200) {
  const encoder = new TextEncoder();
  const streams: ReadableStreamDefaultController<Uint8Array>[] = [];
  const fetchMock = vi.fn(async (_request: Request) => {
    if (status !== 200) return new Response(null, { status });
    return new Response(new ReadableStream({ start: (controller) => void streams.push(controller) }), {
      headers: { 'Content-Type': 'text/event-stream' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  return {
    fetchMock,
    send: (connection: number, text: string) => streams[connection].enqueue(encoder.encode(text)),
    close: (connection: number) => streams[connection].close(),
  };
}

function setup(stallMs = 45_000) {
  client.setConfig({ baseUrl: API });
  TestBed.configureTestingModule({
    providers: [{ provide: STREAM_STALL_MS, useValue: stallMs }, provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideHeyApiClient(client)],
  });
  return { service: TestBed.inject(OrderStreamService), http: TestBed.inject(HttpTestingController) };
}

const pending = { id: 5, productId: 1, quantity: 2, status: 'PENDING' };

describe('OrderStreamService', () => {
  afterEach(() => {
    TestBed.inject(OrderStreamService).stop();
    vi.unstubAllGlobals();
    client.setConfig({ baseUrl: '/' });
  });

  it('loads the orders when the stream opens and updates a status without a reload', async () => {
    const server = fakeServer();
    const { service, http } = setup();

    service.start();
    await vi.waitFor(() => expect(server.fetchMock).toHaveBeenCalled());
    server.send(0, ': connected\n\n');
    (await vi.waitFor(() => http.expectOne(`${API}/orders`))).flush([pending]);
    await vi.waitFor(() => expect(service.orders()).toHaveLength(1));
    expect(service.connection()).toBe('live');
    expect(service.orders()[0].status).toBe('PENDING');

    server.send(0, 'event: order-status\ndata: {"orderId":5,"status":"CONFIRMED"}\n\n');

    await vi.waitFor(() => expect(service.orders()[0].status).toBe('CONFIRMED'));
  });

  it('sends the bearer token with the stream request', async () => {
    const token = fakeToken(3600);
    localStorage.setItem('stockrush.token', token);
    const server = fakeServer();
    const { service } = setup();

    service.start();

    await vi.waitFor(() => expect(server.fetchMock).toHaveBeenCalled());
    const request = server.fetchMock.mock.calls[0][0] as unknown as Request;
    expect(request.headers.get('Authorization')).toBe(`Bearer ${token}`);
    localStorage.clear();
  });

  it('reads the list again when an event names an order it does not know', async () => {
    const server = fakeServer();
    const { service, http } = setup();
    service.start();
    await vi.waitFor(() => expect(server.fetchMock).toHaveBeenCalled());
    server.send(0, ': connected\n\n');
    (await vi.waitFor(() => http.expectOne(`${API}/orders`))).flush([]);

    server.send(0, 'event: order-status\ndata: {"orderId":5,"status":"CANCELLED","cancellationReason":"EXPIRED"}\n\n');
    (await vi.waitFor(() => http.expectOne(`${API}/orders`))).flush([{ ...pending, status: 'CANCELLED', cancellationReason: 'EXPIRED' }]);

    await vi.waitFor(() => expect(service.orders()[0].cancellationReason).toBe('EXPIRED'));
  });

  it('reconnects after the server closes the stream and recovers what it missed', async () => {
    const server = fakeServer();
    const { service, http } = setup();
    service.start();
    await vi.waitFor(() => expect(server.fetchMock).toHaveBeenCalledTimes(1));
    server.send(0, ': connected\n\n');
    (await vi.waitFor(() => http.expectOne(`${API}/orders`))).flush([pending]);
    await vi.waitFor(() => expect(service.orders()).toHaveLength(1));

    server.close(0);
    await vi.waitFor(() => expect(service.connection()).toBe('reconnecting'));
    await vi.waitFor(() => expect(server.fetchMock).toHaveBeenCalledTimes(2), { timeout: 4000 });
    server.send(1, ': connected\n\n');
    (await vi.waitFor(() => http.expectOne(`${API}/orders`))).flush([{ ...pending, status: 'CONFIRMED' }]);

    await vi.waitFor(() => expect(service.orders()[0].status).toBe('CONFIRMED'));
    expect(service.connection()).toBe('live');
  });

  it('reopens a stream that goes silent, which is how a dead connection looks', async () => {
    const server = fakeServer();
    const { service } = setup(300);
    service.start();
    await vi.waitFor(() => expect(server.fetchMock).toHaveBeenCalledTimes(1));
    server.send(0, ': connected\n\n');
    await vi.waitFor(() => expect(service.connection()).toBe('live'));

    // no keep-alive arrives: after the stall limit and the reconnect delay a second connection is opened
    await vi.waitFor(() => expect(server.fetchMock).toHaveBeenCalledTimes(2), { timeout: 4000 });
    expect(service.connection()).toBe('reconnecting');
  });

  it('keeps a stream that keeps receiving keep-alives', async () => {
    const server = fakeServer();
    const { service } = setup(300);
    service.start();
    await vi.waitFor(() => expect(server.fetchMock).toHaveBeenCalledTimes(1));
    server.send(0, ': connected\n\n');

    for (let beat = 0; beat < 5; beat++) {
      await new Promise((resolve) => setTimeout(resolve, 150));
      server.send(0, ': keepalive\n\n');
    }

    expect(server.fetchMock).toHaveBeenCalledTimes(1);
  });

  it('signs out when the stream is refused as unauthorized', async () => {
    fakeServer(401);
    const { service } = setup();
    const logout = vi.spyOn(TestBed.inject(AuthService), 'logout');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    service.start();

    await vi.waitFor(() => expect(logout).toHaveBeenCalled());
    expect(navigate).toHaveBeenCalledWith(['/entrar']);
  });
});
