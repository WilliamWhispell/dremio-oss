/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.planner.logical;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import com.dremio.common.expression.CaseExpression;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.expression.visitors.AbstractExprVisitor;
import com.dremio.common.logical.LogicalPlan;
import com.dremio.common.logical.data.Filter;
import com.dremio.common.logical.data.GroupingAggregate;
import com.dremio.common.logical.data.Join;
import com.dremio.common.logical.data.JoinCondition;
import com.dremio.common.logical.data.Limit;
import com.dremio.common.logical.data.LogicalOperator;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.logical.data.Order;
import com.dremio.common.logical.data.Order.Ordering;
import com.dremio.common.logical.data.Project;
import com.dremio.common.logical.data.Scan;
import com.dremio.common.logical.data.SinkOperator;
import com.dremio.common.logical.data.Store;
import com.dremio.common.logical.data.Union;
import com.dremio.common.logical.data.Values;
import com.dremio.common.logical.data.visitors.AbstractLogicalVisitor;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * This visitor will walk a logical plan and record in a map the list of field references associated to each scan. These
 * can then be used to update scan object to appear to be explicitly fielded for optimization purposes.
 */
public class ScanFieldDeterminer extends AbstractLogicalVisitor<Void, ScanFieldDeterminer.FieldList, RuntimeException> {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ScanFieldDeterminer.class);

  private FieldReferenceFinder finder = new FieldReferenceFinder();
  private Map<Scan, FieldList> scanFields = Maps.newHashMap();


  public static Map<Scan, FieldList> getFieldLists(LogicalPlan plan){
    Collection<SinkOperator> ops = plan.getGraph().getRoots();
    Preconditions.checkArgument(ops.size() == 1, "Scan Field determiner currently only works with plans that have a single root.");
    ScanFieldDeterminer sfd = new ScanFieldDeterminer();
    ops.iterator().next().accept(sfd, new FieldList());
    return sfd.scanFields;
  }

  private ScanFieldDeterminer(){
  }

  public static class FieldList {
    private Set<SchemaPath> projected = Sets.newHashSet();
    private Set<SchemaPath> referenced = Sets.newHashSet();

    public void addProjected(SchemaPath path) {
      projected.add(path);
    }

    public void addReferenced(SchemaPath path) {
      referenced.add(path);
    }

    public void addReferenced(Collection<SchemaPath> paths) {
      referenced.addAll(paths);
    }

    public void addProjected(Collection<SchemaPath> paths) {
      projected.addAll(paths);
    }

    @Override
    public FieldList clone() {
      FieldList newList = new FieldList();
      for (SchemaPath p : projected) {
        newList.addProjected(p);
      }
      for (SchemaPath p : referenced) {
        newList.addReferenced(p);
      }
      return newList;
    }
  }

  @Override
  public Void visitScan(Scan scan, FieldList value) {
    if (value == null) {
      scanFields.put(scan, new FieldList());
    } else {
      scanFields.put(scan, value);
    }
    return null;
  }

  @Override
  public Void visitStore(Store store, FieldList value) {
    store.getInput().accept(this, value);
    return null;
  }

  @Override
  public Void visitGroupingAggregate(GroupingAggregate groupBy, FieldList value) {
    FieldList list = new FieldList();
    for (NamedExpression e : groupBy.getExprs()) {
      list.addProjected(e.getExpr().accept(finder, null));
    }
    for (NamedExpression e : groupBy.getKeys()) {
      list.addProjected(e.getExpr().accept(finder, null));
    }
    groupBy.getInput().accept(this, list);
    return null;
  }

  @Override
  public Void visitFilter(Filter filter, FieldList value) {
    value.addReferenced(filter.getExpr().accept(finder, null));
    return null;
  }

  @Override
  public Void visitProject(Project project, FieldList value) {
    FieldList fl = new FieldList();
    for (NamedExpression e : project.getSelections()) {
      fl.addProjected(e.getExpr().accept(finder, null));
    }
    return null;
  }

  @Override
  public Void visitValues(Values constant, FieldList value) {
    return null;
  }

  @Override
  public Void visitOrder(Order order, FieldList fl) {
    for (Ordering o : order.getOrderings()) {
      fl.addReferenced(o.getExpr().accept(finder, null));
    }
    return null;
  }

  @Override
  public Void visitJoin(Join join, FieldList fl) {
    {
      FieldList leftList = fl.clone();
      for (JoinCondition c : join.getConditions()) {
        leftList.addReferenced(c.getLeft().accept(finder, null));
      }
      join.getLeft().accept(this, leftList);
    }

    {
      FieldList rightList = fl.clone();
      for (JoinCondition c : join.getConditions()) {
        rightList.addReferenced(c.getRight().accept(finder, null));
      }
      join.getLeft().accept(this, rightList);
    }
    return null;
  }

  @Override
  public Void visitLimit(Limit limit, FieldList value) {
    limit.getInput().accept(this, value);
    return null;
  }

  @Override
  public Void visitUnion(Union union, FieldList value) {
    for (LogicalOperator o : union.getInputs()) {
      o.accept(this, value.clone());
    }
    return null;
  }


  /**
   * Search through a LogicalExpression, finding all internal schema path references and returning them in a set.
   */
  private class FieldReferenceFinder extends AbstractExprVisitor<Set<SchemaPath>, Void, RuntimeException> {

    @Override
    public Set<SchemaPath> visitSchemaPath(SchemaPath path, Void value) {
      Set<SchemaPath> set = Sets.newHashSet();
      set.add(path);
      return set;
    }

    @Override
    public Set<SchemaPath> visitUnknown(LogicalExpression e, Void value) {
      Set<SchemaPath> paths = Sets.newHashSet();
      for (LogicalExpression ex : e) {
        paths.addAll(ex.accept(this, null));
      }
      return paths;
    }

    @Override
    public Set<SchemaPath> visitCaseExpression(CaseExpression caseExpression, Void value) throws RuntimeException {
      return visitUnknown(caseExpression, value);
    }
  }
}
