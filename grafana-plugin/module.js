System.register(["@grafana/data", "@grafana/ui", "react"], function (exports) {
  "use strict";
  var DataSourcePlugin, DataSourceApi, toDataFrame, CodeEditor, Button, InlineField, Input, React;
  return {
    setters: [
      function (data) {
        DataSourcePlugin = data.DataSourcePlugin;
        DataSourceApi = data.DataSourceApi;
        toDataFrame = data.toDataFrame;
      },
      function (ui) {
        CodeEditor = ui.CodeEditor;
        Button = ui.Button;
        InlineField = ui.InlineField;
        Input = ui.Input;
      },
      function (react) {
        React = react;
      },
    ],
    execute: function () {
      function connectorUrl(settings) {
        var configured = settings && settings.jsonData && settings.jsonData.url;
        var url = (configured || "http://localhost:8081").trim();
        return url.endsWith("/") ? url.slice(0, -1) : url;
      }

      function SqlDatasource(instanceSettings) {
        DataSourceApi.call(this, instanceSettings);
        this.baseUrl = connectorUrl(instanceSettings);
      }

      SqlDatasource.prototype = Object.create(DataSourceApi.prototype);
      SqlDatasource.prototype.constructor = SqlDatasource;

      SqlDatasource.prototype.query = async function (request) {
        var frames = [];
        for (var i = 0; i < request.targets.length; i++) {
          var target = request.targets[i];
          if (target.hide || !target.sql || !String(target.sql).trim()) {
            continue;
          }
          var response = await fetch(this.baseUrl + "/query", {
            method: "POST",
            headers: { "Content-Type": "application/json", Accept: "application/json" },
            body: JSON.stringify({
              sql: target.sql,
              from: request.range.from.valueOf(),
              to: request.range.to.valueOf(),
              format: "frames",
            }),
          });
          var payload = await response.json();
          if (!response.ok) {
            throw new Error(payload && payload.message ? payload.message : response.statusText);
          }
          var received = payload.frames || [];
          for (var f = 0; f < received.length; f++) {
            var frame = toDataFrame(received[f]);
            frame.refId = target.refId;
            frames.push(frame);
          }
        }
        return { data: frames };
      };

      SqlDatasource.prototype.testDatasource = async function () {
        try {
          var response = await fetch(this.baseUrl + "/health");
          var payload = await response.json();
          if (response.ok && payload.status === "up") {
            return { status: "success", message: "Connector is up" };
          }
          return { status: "error", message: (payload && payload.message) || response.statusText };
        } catch (error) {
          return { status: "error", message: error instanceof Error ? error.message : String(error) };
        }
      };

      function QueryEditor(props) {
        return React.createElement(
          "div",
          null,
          React.createElement(CodeEditor, {
            language: "sql",
            width: "100%",
            height: 220,
            showLineNumbers: true,
            showMiniMap: false,
            value: props.query.sql || "",
            onChange: function (value) {
              props.onChange(Object.assign({}, props.query, { sql: value }));
            },
            onBlur: function (value) {
              props.onChange(Object.assign({}, props.query, { sql: value }));
            },
            onSave: function (value) {
              props.onChange(Object.assign({}, props.query, { sql: value }));
              props.onRunQuery();
            },
          }),
          React.createElement(
            "div",
            { style: { marginTop: 8 } },
            React.createElement(Button, { size: "sm", onClick: function () { props.onRunQuery(); } }, "Run query")
          )
        );
      }

      function ConfigEditor(props) {
        var url = (props.options.jsonData && props.options.jsonData.url) || "";
        return React.createElement(
          InlineField,
          { label: "URL", tooltip: "grafana-connector, reachable from the browser", grow: true },
          React.createElement(Input, {
            value: url,
            placeholder: "http://localhost:8081",
            onChange: function (event) {
              props.onOptionsChange(Object.assign({}, props.options, {
                jsonData: Object.assign({}, props.options.jsonData, { url: event.currentTarget.value }),
              }));
            },
          })
        );
      }

      var plugin = new DataSourcePlugin(SqlDatasource).setQueryEditor(QueryEditor).setConfigEditor(ConfigEditor);
      exports("plugin", plugin);
    },
  };
});
