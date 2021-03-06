/*
 * Copyright 2014 DataGenerator Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.finra.datagenerator.engine.negscxml;

import org.apache.commons.scxml.env.jsp.ELContext;
import org.apache.commons.scxml.env.jsp.ELEvaluator;
import org.apache.commons.scxml.io.SCXMLParser;
import org.apache.commons.scxml.model.Action;
import org.apache.commons.scxml.model.Assign;
import org.apache.commons.scxml.model.CustomAction;
import org.apache.commons.scxml.model.ModelException;
import org.apache.commons.scxml.model.OnEntry;
import org.apache.commons.scxml.model.SCXML;
import org.apache.commons.scxml.model.TransitionTarget;
import org.finra.datagenerator.distributor.SearchDistributor;
import org.finra.datagenerator.engine.Engine;
import org.finra.datagenerator.engine.Frontier;
import org.finra.datagenerator.engine.scxml.Transform;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Marshall Peters
 * Date: 8/25/14
 */
public class NegSCXMLEngine extends NegSCXMLCommons implements Engine {

    private SCXML model;
    private int bootStrapMin;
    private int negative;

    /**
     * Constructor
     *
     * @param negative the required number of negative variable assignments
     */
    public NegSCXMLEngine(final int negative) {
        super();

        ELEvaluator elEvaluator = new ELEvaluator();
        ELContext context = new ELContext();

        this.setEvaluator(elEvaluator);
        this.setRootContext(context);

        this.negative = negative;
    }

    /**
     * Searches the model for all variable assignments and makes a default map of those variables, setting them to ""
     *
     * @return the default variable assignment map
     */
    private Map<String, String> fillInitialVariables() {
        Map<String, TransitionTarget> targets = model.getChildren();

        Set<String> variables = new HashSet<>();
        for (TransitionTarget target : targets.values()) {
            OnEntry entry = target.getOnEntry();
            List<Action> actions = entry.getActions();
            for (Action action : actions) {
                if (action instanceof Assign) {
                    String variable = ((Assign) action).getName();
                    variables.add(variable);
                }
            }
        }

        Map<String, String> result = new HashMap<>();
        for (String variable : variables) {
            result.put(variable, "");
        }

        return result;
    }

    /**
     * Performs a partial BFS on model until the search frontier reaches the desired bootstrap size
     *
     * @param min the desired bootstrap size
     * @return a list of found PossibleState
     * @throws ModelException if the desired bootstrap can not be reached
     */
    public List<NegPossibleState> bfs(int min) throws ModelException {
        List<NegPossibleState> bootStrap = new LinkedList<>();

        TransitionTarget initial = model.getInitialTarget();
        NegPossibleState initialState = new NegPossibleState(initial, fillInitialVariables(), new HashSet<String>());
        bootStrap.add(initialState);

        while (bootStrap.size() < min) {
            NegPossibleState state = bootStrap.remove(0);

            if (state.nextState.getId().equalsIgnoreCase("end")) {
                throw new ModelException("Could not achieve required bootstrap without reaching end state");
            }

            //produce purely positive scenarios
            expandPositive(state, bootStrap);

            //possible state does not have enough negative values yet, produce scenarios with some
            if (state.negVariable.size() < negative) {
                expandNegative(state, bootStrap, negative - state.negVariable.size());
            }
        }

        return bootStrap;
    }

    /**
     * Performs the BFS and gives the results to a distributor to distribute
     *
     * @param distributor the distributor
     */
    public void process(SearchDistributor distributor) {
        List<NegPossibleState> bootStrap;
        try {
            bootStrap = bfs(bootStrapMin);
        } catch (ModelException e) {
            bootStrap = new LinkedList<>();
        }

        List<Frontier> frontiers = new LinkedList<>();
        for (NegPossibleState p : bootStrap) {
            NegSCXMLFrontier dge = new NegSCXMLFrontier(p, model, negative);
            frontiers.add(dge);
        }

        distributor.distribute(frontiers);
    }

    private List<CustomAction> customActions() {
        List<CustomAction> actions = new LinkedList<>();
        CustomAction tra = new CustomAction("org.finra.datagenerator", "transform", Transform.class);
        actions.add(tra);
        CustomAction neg = new CustomAction("org.finra.datagenerator", "negative", NegativeAssign.class);
        actions.add(neg);
        return actions;
    }

    /**
     * Sets the SCXML model with an InputStream
     *
     * @param inputFileStream the model input stream
     */
    public void setModelByInputFileStream(InputStream inputFileStream) {
        try {
            this.model = SCXMLParser.parse(new InputSource(inputFileStream), null, customActions());
            this.setStateMachine(this.model);
        } catch (IOException | SAXException | ModelException e) {
            e.printStackTrace();
        }
    }

    /**
     * Sets the SCXML model with a string
     *
     * @param model the model text
     */
    public void setModelByText(String model) {
        try {
            InputStream is = new ByteArrayInputStream(model.getBytes());
            this.model = SCXMLParser.parse(new InputSource(is), null, customActions());
            this.setStateMachine(this.model);
        } catch (IOException | SAXException | ModelException e) {
            e.printStackTrace();
        }
    }

    /**
     * bootstrapMin setter
     *
     * @param min sets the desired bootstrap min
     * @return this
     */
    public Engine setBootstrapMin(int min) {
        bootStrapMin = min;
        return this;
    }

}

