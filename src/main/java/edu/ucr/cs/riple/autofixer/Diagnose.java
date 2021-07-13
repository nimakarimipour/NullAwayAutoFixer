package edu.ucr.cs.riple.autofixer;

import static edu.ucr.cs.riple.autofixer.util.Utility.*;

import com.google.common.base.Preconditions;
import edu.ucr.cs.riple.autofixer.errors.Bank;
import edu.ucr.cs.riple.autofixer.explorers.ClassFieldExplorer;
import edu.ucr.cs.riple.autofixer.explorers.Explorer;
import edu.ucr.cs.riple.autofixer.explorers.MethodParamExplorer;
import edu.ucr.cs.riple.autofixer.explorers.MethodReturnExplorer;
import edu.ucr.cs.riple.autofixer.metadata.CallGraph;
import edu.ucr.cs.riple.autofixer.metadata.FieldGraph;
import edu.ucr.cs.riple.autofixer.metadata.MethodInheritanceTree;
import edu.ucr.cs.riple.autofixer.metadata.MethodNode;
import edu.ucr.cs.riple.autofixer.nullaway.AutoFixConfig;
import edu.ucr.cs.riple.injector.Fix;
import edu.ucr.cs.riple.injector.Injector;
import edu.ucr.cs.riple.injector.WorkList;
import edu.ucr.cs.riple.injector.WorkListBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class Diagnose {

  String out_dir;
  String buildCommand;
  String fixPath;
  String diagnosePath;
  List<DiagnoseReport> finishedReports;
  Injector injector;
  Bank bank;
  List<Explorer> explorers;
  public CallGraph callGraph;
  public MethodInheritanceTree methodInheritanceTree;
  public FieldGraph fieldGraph;

  public void start(String buildCommand, String out_dir, boolean optimized) {
    System.out.println("Diagnose Started...");
    this.out_dir = out_dir;
    init(buildCommand);
    injector = Injector.builder().setMode(Injector.MODE.BATCH).build();
    System.out.println("Starting preparation");
    prepare(out_dir, optimized);
    List<WorkList> workListLists = new WorkListBuilder(diagnosePath).getWorkLists();
    try {
      for (WorkList workList : workListLists) {
        for (Fix fix : workList.getFixes()) {
          if (finishedReports.stream().anyMatch(diagnoseReport -> diagnoseReport.fix.equals(fix))) {
            continue;
          }
          List<Fix> appliedFixes = analyze(fix);
          remove(appliedFixes);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    writeReports(finishedReports);
  }

  private void init(String buildCommand) {
    this.buildCommand = buildCommand;
    this.fixPath = out_dir + "/fixes.csv";
    this.diagnosePath = out_dir + "/diagnose.json";
    this.finishedReports = new ArrayList<>();
    this.methodInheritanceTree = new MethodInheritanceTree(out_dir + "/method_info.csv");
    this.callGraph = new CallGraph(out_dir + "/call_graph.csv");
    this.fieldGraph = new FieldGraph(out_dir + "/field_graph.csv");
    this.explorers = new ArrayList<>();
    bank = new Bank();
    explorers.add(new MethodParamExplorer(this, bank));
    explorers.add(new ClassFieldExplorer(this, bank));
    explorers.add(new MethodReturnExplorer(this, bank));
  }

  private void remove(List<Fix> fixes) {
    List<Fix> toRemove = new ArrayList<>();
    for (Fix fix : fixes) {
      Fix removeFix =
          new Fix(
              fix.annotation, fix.method, fix.param, fix.location, fix.className, fix.uri, "false");
      toRemove.add(removeFix);
    }
    injector.start(Collections.singletonList(new WorkList(toRemove)));
  }

  private List<Fix> analyze(Fix fix) {
    System.out.println("FIX TYPE IS: " + fix.location);
    List<Fix> suggestedFix = new ArrayList<>();
    DiagnoseReport diagnoseReport = null;
    suggestedFix.add(fix);
    //    protectInheritanceRules(fix, suggestedFix);
    injector.start(Collections.singletonList(new WorkList(suggestedFix)));
    for (Explorer explorer : explorers) {
      if (explorer.isApplicable(fix)) {
        diagnoseReport = explorer.effect(fix);
        break;
      }
    }
    Preconditions.checkNotNull(diagnoseReport);
    finishedReports.add(diagnoseReport);
    return suggestedFix;
  }

  private void protectInheritanceRules(Fix fix, List<Fix> suggestedFix) {
    if (fix.location.equals("METHOD_PARAM")) {
      List<MethodNode> subMethods =
          methodInheritanceTree.getSubMethods(fix.method, fix.className, true);
      for (MethodNode info : subMethods) {
        suggestedFix.add(
            new Fix(
                fix.annotation,
                info.method,
                fix.param,
                fix.location,
                info.clazz,
                info.uri,
                fix.inject));
      }
    }
    if (fix.location.equals("METHOD_RETURN")) {
      List<MethodNode> subMethods =
          methodInheritanceTree.getSuperMethods(fix.method, fix.className, true);
      for (MethodNode info : subMethods) {
        suggestedFix.add(
            new Fix(
                fix.annotation,
                info.method,
                fix.param,
                fix.location,
                info.clazz,
                info.uri,
                fix.inject));
      }
    }
  }

  @SuppressWarnings("ALL")
  private void prepare(String out_dir, boolean optimized) {
    try {
      System.out.println("Preparing project: with optimization flag:" + optimized);
      executeCommand(buildCommand);
      if (!new File(fixPath).exists()) {
        JSONObject toDiagnose = new JSONObject();
        toDiagnose.put("fixes", new JSONArray());
        FileWriter writer = new FileWriter(diagnosePath);
        writer.write(toDiagnose.toJSONString());
        writer.flush();
        System.out.println("No new fixes from NullAway, created empty list.");
        return;
      }
      new File(diagnosePath).delete();
      convertCSVToJSON(this.fixPath, out_dir + "/fixes.json");
      System.out.println("Deleted old diagnose file.");
      System.out.println("Making new diagnose.json.");
      if (!optimized) {
        executeCommand("cp " + this.fixPath + " " + this.diagnosePath);
        convertCSVToJSON(diagnosePath, diagnosePath);
      } else {
        try {
          System.out.println("Removing already diagnosed fixes...");
          Object obj = new JSONParser().parse(new FileReader(out_dir + "/fixes.json"));
          JSONObject fixes = (JSONObject) obj;
          obj = new JSONParser().parse(new FileReader(out_dir + "/diagnosed.json"));
          JSONObject diagnosed = (JSONObject) obj;
          JSONArray fixes_array = (JSONArray) fixes.get("fixes");
          JSONArray diagnosed_array = (JSONArray) diagnosed.get("fixes");
          fixes_array.removeAll(diagnosed_array);
          JSONObject toDiagnose = new JSONObject();
          toDiagnose.put("fixes", fixes_array);
          FileWriter writer = new FileWriter(diagnosePath);
          writer.write(toDiagnose.toJSONString());
          writer.flush();
        } catch (RuntimeException exception) {
          System.out.println("Exception happened while optimizing suggested fixes.");
          System.out.println("Continuing...");
          executeCommand("cp " + fixPath + " " + diagnosePath);
        }
      }
      System.out.println("Made.");
      System.out.println("Preparation done");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void buildProject() {
    try {
      executeCommand(buildCommand);
    } catch (Exception e) {
      throw new RuntimeException("Could not run command: " + buildCommand);
    }
  }

  public void writeConfig(AutoFixConfig.AutoFixConfigWriter writer) {
    writer.write(out_dir + "/explorer.config");
  }
}