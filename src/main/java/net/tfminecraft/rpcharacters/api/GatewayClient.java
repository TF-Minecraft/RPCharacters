package net.tfminecraft.rpcharacters.api;

/**
 * Reflective bridge to TFMCWeb {@code ProvinceSystemGateway}.
 * RPCharacters depends on TFMCWeb at runtime ({@code plugin.yml depend}).
 */
public final class GatewayClient {

	private GatewayClient() {}

	public static final class Result {
		public final boolean ok;
		public final String body;
		public final String error;

		private Result(boolean ok, String body, String error) {
			this.ok = ok;
			this.body = body;
			this.error = error;
		}

		public static Result success(String body) {
			return new Result(true, body == null ? "" : body, null);
		}

		public static Result fail(String error) {
			return new Result(false, null, error);
		}
	}

	public static final class BytesDownload {
		public final boolean ok;
		public final byte[] data;
		public final String error;

		private BytesDownload(boolean ok, byte[] data, String error) {
			this.ok = ok;
			this.data = data;
			this.error = error;
		}

		public static BytesDownload success(byte[] data) {
			return new BytesDownload(true, data, null);
		}

		public static BytesDownload fail(String error) {
			return new BytesDownload(false, null, error);
		}
	}

	/**
	 * TFMCWeb realm id, or null when TFMCWeb is unreachable. Callers that delete
	 * data must abort on null instead of assuming {@code main}.
	 */
	public static String realmId() {
		try {
			Class<?> cls = Class.forName("net.tfminecraft.tfmcweb.TFMCWeb");
			Object raw = cls.getMethod("getRealmId").invoke(null);
			String realm = raw == null ? "" : String.valueOf(raw).trim();
			return realm.isEmpty() ? null : realm;
		} catch (Throwable t) {
			return null;
		}
	}

	public static Result request(String method, String path, String jsonBody) {
		try {
			Class<?> cls = Class.forName(
				"net.tfminecraft.tfmcweb.api.ProvinceSystemGateway"
			);
			Object raw = cls.getMethod(
				"request",
				String.class,
				String.class,
				String.class
			).invoke(null, method, path, jsonBody);
			return fromGatewayResult(raw);
		} catch (Throwable t) {
			return Result.fail(failMessage(t));
		}
	}

	public static Result requestBytes(
		String method,
		String path,
		byte[] body,
		String contentType
	) {
		try {
			Class<?> cls = Class.forName(
				"net.tfminecraft.tfmcweb.api.ProvinceSystemGateway"
			);
			Object raw = cls.getMethod(
				"requestBytes",
				String.class,
				String.class,
				byte[].class,
				String.class
			).invoke(null, method, path, body, contentType);
			return fromGatewayResult(raw);
		} catch (Throwable t) {
			return Result.fail(failMessage(t));
		}
	}

	public static BytesDownload download(String path) {
		try {
			Class<?> cls = Class.forName(
				"net.tfminecraft.tfmcweb.api.ProvinceSystemGateway"
			);
			Object raw = cls.getMethod("download", String.class).invoke(null, path);
			boolean ok = Boolean.TRUE.equals(
				raw.getClass().getField("ok").get(raw)
			);
			if (ok) {
				return BytesDownload.success(
					(byte[]) raw.getClass().getField("data").get(raw)
				);
			}
			Object err = raw.getClass().getField("error").get(raw);
			return BytesDownload.fail(err == null ? "download failed" : String.valueOf(err));
		} catch (Throwable t) {
			return BytesDownload.fail(failMessage(t));
		}
	}

	private static Result fromGatewayResult(Object raw) throws Exception {
		boolean ok = Boolean.TRUE.equals(
			raw.getClass().getField("ok").get(raw)
		);
		if (ok) {
			Object body = raw.getClass().getField("body").get(raw);
			return Result.success(body == null ? "" : String.valueOf(body));
		}
		Object err = raw.getClass().getField("error").get(raw);
		return Result.fail(err == null ? "request failed" : String.valueOf(err));
	}

	private static String failMessage(Throwable t) {
		Throwable c = t.getCause() != null ? t.getCause() : t;
		String msg = c.getMessage();
		if (msg == null || msg.isBlank()) {
			msg = c.getClass().getSimpleName();
		}
		return "TFMCWeb gateway unavailable: " + msg;
	}
}
