package edu.ucr.cs.riple.autofixer.explorers;

import edu.ucr.cs.riple.autofixer.Diagnose;
import edu.ucr.cs.riple.autofixer.DiagnoseReport;
import edu.ucr.cs.riple.autofixer.errors.Bank;
import edu.ucr.cs.riple.autofixer.metadata.CallGraph;
import edu.ucr.cs.riple.injector.Fix;

public class MethodReturnExplorer extends Explorer {

  CallGraph callGraph;

  public MethodReturnExplorer(Diagnose diagnose, Bank bank) {
    super(diagnose, bank);
    callGraph = diagnose.callGraph;
  }

  @Override
  public DiagnoseReport effect(Fix fix) {
    return effectByScope(fix, callGraph.getUserClassesOfMethod(fix.method, fix.className));
  }

  @Override
  public boolean isApplicable(Fix fix) {
    return fix.location.equals("METHOD_RETURN");
  }
}