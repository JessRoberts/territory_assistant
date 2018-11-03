// Copyright © 2015-2018 Esko Luontola
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

/* @flow */

import "@babel/polyfill"
import React from "react";
import ReactDOM from "react-dom";
import {Provider} from "react-redux";
import {applyMiddleware, createStore} from "redux";
import {createLogger} from "redux-logger";
import reducers from "./reducers";
import history from "./history";
import router from "./router";
import routes from "./routes";
import {IntlProvider} from "react-intl";
import {language, messages} from "./intl";
import {congregationChanged, mapRastersLoaded} from "./configActions";
import {mapRasters} from "./maps/mapOptions";
import {savedCongregationId} from "./congregation";

const logger = createLogger();
const store = createStore(reducers, applyMiddleware(logger));
const root = document.getElementById('root');
if (root === null) {
  throw new Error('root element not found');
}

function renderComponent(component) {
  ReactDOM.render(
    <IntlProvider locale={language} messages={messages}>
      <Provider store={store}>
        {component}
      </Provider>
    </IntlProvider>, root);
}

async function renderNormalPage(location) {
  const route = await router.resolve(routes, {...location, store});
  renderComponent(route);
}

async function renderErrorPage(location, error) {
  console.error("Error in rendering " + location.pathname + "\n", error);
  const route = await router.resolve(routes, {...location, store, error});
  renderComponent(route);
}

function handleRedirect(location, {redirect, replace}) {
  console.info('Redirecting from', location, 'to', redirect);
  if (replace) {
    history.replace(redirect);
  } else {
    history.push(redirect);
  }
}

async function render(location) {
  try {
    await renderNormalPage(location);
  } catch (error) {
    if (error.redirect) {
      handleRedirect(location, error);
    } else {
      await renderErrorPage(location, error);
    }
  }
}

const congregationId = savedCongregationId();
if (congregationId) {
  store.dispatch(congregationChanged(congregationId));
}
store.dispatch(mapRastersLoaded(mapRasters));

render(history.location);
history.listen((location, action) => {
  console.log(`Current URL is now ${location.pathname}${location.search}${location.hash} (${action})`);
  render(location);
});
