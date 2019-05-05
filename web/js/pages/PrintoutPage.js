// Copyright © 2015-2019 Esko Luontola
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

/* @flow */

import React from "react";
import PrintOptionsForm from "../prints/PrintOptionsForm";

const PrintoutPage = ({congregationId}) => {
  return (
    <>
      <div className="no-print">
        <h1>Printouts</h1>
      </div>
      <PrintOptionsForm congregationId={congregationId}/>
    </>
  );
};

export default PrintoutPage;
