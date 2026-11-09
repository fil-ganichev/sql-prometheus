import http from "k6/http";
import { check } from "k6";

const base = (__ENV.BASE_URL || "http://demo:8080").replace(/\/$/, "");

export const options = {
  vus: 5,
  duration: "3m",
  thresholds: {
    checks: ["rate>0.9"],
  },
};

const jsonHeaders = {
  headers: { Accept: "application/json", "Content-Type": "application/json" },
};

export default function () {
  const action = actions[Math.floor(Math.random() * actions.length)];
  action();
}

const actions = [
  categories,
  categories,
  products,
  product,
  search,
  profile,
  cart,
  orders,
  missingProduct,
  missingUser,
  missingOrder,
  blankSearch,
  emptyOrder,
  unknownProduct,
  staleDetails,
  staleProfile,
  staleCart,
  unknownBuyer,
  insufficientStock,
  emptyCart,
];

function categories() {
  const response = http.get(`${base}/api/v1/catalog/categories`, tagged("categories"));
  check(response, { "categories 200": (r) => r.status === 200 });
}

function products() {
  const response = http.get(`${base}/api/v1/catalog/products?limit=5`, tagged("products"));
  check(response, { "products 200": (r) => r.status === 200 });
}

function product() {
  const response = http.get(`${base}/api/v1/products/1`, tagged("product"));
  check(response, { "product 200": (r) => r.status === 200 });
}

function search() {
  const response = http.get(`${base}/api/v1/catalog/products/search?q=Coffee`, tagged("search"));
  check(response, { "search 200": (r) => r.status === 200 });
}

function profile() {
  const response = http.get(`${base}/api/v1/users/1/profile`, tagged("profile"));
  check(response, { "profile 200": (r) => r.status === 200 });
}

function cart() {
  const response = http.get(`${base}/api/v1/users/1/cart`, tagged("cart"));
  check(response, { "cart 200": (r) => r.status === 200 });
}

function orders() {
  const response = http.get(`${base}/api/v1/users/1/orders?limit=5`, tagged("orders"));
  check(response, { "orders 200": (r) => r.status === 200 });
}

function missingProduct() {
  const response = http.get(`${base}/api/v1/products/999999`, tagged("missing-product"));
  check(response, { "missing product 404": (r) => r.status === 404 });
}

function missingUser() {
  const response = http.get(`${base}/api/v1/users/999999/profile`, tagged("missing-user"));
  check(response, { "missing user 404": (r) => r.status === 404 });
}

function missingOrder() {
  const response = http.get(`${base}/api/v1/orders/999999`, tagged("missing-order"));
  check(response, { "missing order 404": (r) => r.status === 404 });
}

function blankSearch() {
  const response = http.get(`${base}/api/v1/catalog/products/search?q=%20`, tagged("blank-search"));
  check(response, { "blank search 400": (r) => r.status === 400 });
}

function emptyOrder() {
  const response = http.post(
    `${base}/api/v1/orders`,
    JSON.stringify({ userId: 1, items: [] }),
    tagged("empty-order")
  );
  check(response, { "empty order 400": (r) => r.status === 400 });
}

function unknownProduct() {
  const response = http.post(
    `${base}/api/v1/orders`,
    JSON.stringify({ userId: 1, items: [{ productId: 999999, quantity: 1 }] }),
    tagged("unknown-product")
  );
  check(response, { "unknown product 400": (r) => r.status === 400 });
}

function staleDetails() {
  const response = http.patch(
    `${base}/api/v1/products/2/details`,
    JSON.stringify({ version: 999, description: "stale write" }),
    tagged("stale-details")
  );
  check(response, { "stale details 409": (r) => r.status === 409 });
}

function staleProfile() {
  const response = http.put(
    `${base}/api/v1/users/1/profile`,
    JSON.stringify({ username: "anna.adler", email: "anna.adler@example.com", version: 999 }),
    tagged("stale-profile")
  );
  check(response, { "stale profile 409": (r) => r.status === 409 });
}

function staleCart() {
  const response = http.put(
    `${base}/api/v1/users/2/cart`,
    JSON.stringify({ version: 999, items: [{ productId: 1, quantity: 1 }] }),
    tagged("stale-cart")
  );
  check(response, { "stale cart 409": (r) => r.status === 409 });
}

function unknownBuyer() {
  const response = http.post(
    `${base}/api/v1/orders`,
    JSON.stringify({ userId: 999999, items: [{ productId: 1, quantity: 1 }] }),
    tagged("unknown-buyer")
  );
  check(response, {
    "unknown buyer 200": (r) => r.status === 200,
    "unknown buyer code": (r) => r.json("code") === "USER_NOT_FOUND",
  });
}

function insufficientStock() {
  const response = http.post(
    `${base}/api/v1/orders`,
    JSON.stringify({ userId: 1, items: [{ productId: 5, quantity: 1 }] }),
    tagged("insufficient-stock")
  );
  check(response, {
    "insufficient stock 200": (r) => r.status === 200,
    "insufficient stock code": (r) => r.json("code") === "INSUFFICIENT_STOCK",
  });
}

function emptyCart() {
  http.get(`${base}/api/v1/users/50/cart`, tagged("empty-cart-read"));
  const response = http.post(
    `${base}/api/v1/orders/from-cart`,
    JSON.stringify({ userId: 50, cartVersion: 1 }),
    tagged("empty-cart")
  );
  check(response, {
    "empty cart 200": (r) => r.status === 200,
    "empty cart code": (r) => r.json("code") === "EMPTY_CART",
  });
}

function tagged(name) {
  return {
    headers: jsonHeaders.headers,
    tags: { name },
  };
}
